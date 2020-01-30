package eu.telecomnancy.semantic;

import eu.telecomnancy.Algol60Parser;
import eu.telecomnancy.ast.*;
import eu.telecomnancy.symbols.*;
import eu.telecomnancy.symbols.Label;
import eu.telecomnancy.symbols.SymbolTable;
import eu.telecomnancy.symbols.Type;
import eu.telecomnancy.symbols.UndeclaredLabel;
import eu.telecomnancy.symbols.Variable;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SemanticAnalysisVisitor implements ASTVisitor<Type> {

    private List<SemanticException> exceptions;
    private SymbolTable currentSymbolTable;
    private Set<UndeclaredLabel> undeclaredLabels;
    private Set<Label> declaredLabels;
    @Deprecated private Map<Type, Set<Type>> typeCompat;

    public SemanticAnalysisVisitor(SymbolTable symbolTable) {
        if (symbolTable == null) {
            throw new IllegalArgumentException("Symbol table cannot be null");
        }
        this.currentSymbolTable = symbolTable;
        this.exceptions = new ArrayList<>();
        this.undeclaredLabels = new LinkedHashSet<>();
        this.declaredLabels = new LinkedHashSet<>();
        this.typeCompat = new HashMap<>();
        typeCompat.put(Type.REAL, new HashSet<>(Arrays.asList(Type.INTEGER, Type.REAL)));
        typeCompat.put(Type.INTEGER, new HashSet<>(Arrays.asList(Type.INTEGER, Type.REAL)));
        typeCompat.put(Type.VOID, new HashSet<>(Collections.emptyList()));
        typeCompat.put(Type.STRING, new HashSet<>(Collections.singletonList(Type.STRING)));
    }

    public List<SemanticException> getExceptions() {
        return exceptions;
    }

    public boolean hasExceptions() {
        return !exceptions.isEmpty();
    }

    private void checkLabelDeclarations() {
        Set<String> declaredLabelIdentifiers =
                declaredLabels.stream().map(Symbol::getIdentifier).collect(Collectors.toSet());

        // Checking that all the undeclaredLabels have been declared later
        for (UndeclaredLabel undeclaredLabel : undeclaredLabels) {
            String identifier = undeclaredLabel.getIdentifier();
            if (!declaredLabelIdentifiers.contains(identifier)) {
                exceptions.add(
                        new SymbolNotDeclaredException(
                                String.format("Label '%s' is used but not declared.", identifier),
                                undeclaredLabel.getTree()));
            }
        }
    }

    private void pushTable() {
        currentSymbolTable = currentSymbolTable.createChild();
    }

    private void popTable() {
        currentSymbolTable = currentSymbolTable.getParent();
    }

    @Override
    public Type visit(DefaultAST ast) {
        for (DefaultAST t : ast) {
            t.accept(this);
        }
        return Type.VOID;
    }

    @Override
    public Type visit(RootAST ast) {
        ast.getChild(0).accept(this);
        // Final operations
        checkLabelDeclarations();
        exceptions.sort(Comparator.comparingInt(SemanticException::getLine));
        return Type.VOID;
    }

    @Override
    public Type visit(BlockAST ast) {
        pushTable();
        for (DefaultAST t : ast) {
            try {
                t.accept(this);
            } catch (SemanticException e) {
                exceptions.add(e);
            }
        }
        popTable();
        return Type.VOID;
    }

    @Override
    public Type visit(VarDecAST ast) {
        Type type = Type.fromString(ast.getChild(0).getText());
        DefaultAST idList = ast.getChild(1);

        for (DefaultAST t : idList) {
            String name = t.getText();
            if (currentSymbolTable.isDeclaredInScope(name)) {
                throw new SymbolRedeclarationException(
                        String.format("Variable '%s' is already declared in scope", name), t);
            }
            Variable variable = new Variable(name, type);
            currentSymbolTable.define(variable);
        }

        return Type.VOID;
    }

    @Override
    public Type visit(ProcDecAST ast) {
        SymbolTable baseSymbolTable = currentSymbolTable;

        DefaultAST procHeading = ast.findFirst(Algol60Parser.PROC_HEADING);
        DefaultAST block = ast.findFirst(Algol60Parser.BLOCK);
        DefaultAST typeAST = ast.findFirst(Algol60Parser.TYPE);

        Type procType = typeAST != null ? Type.fromString(typeAST.getText()) : Type.VOID;
        String procName = procHeading.getChild(0).getText();

        Symbol conflicting = currentSymbolTable.resolveInScope(procName);
        if (conflicting != null) {
            String msg =
                    String.format(
                            "Identifier '%s' is already used by %s in this scope",
                            procName, conflicting.getKind().withPronoun());
            exceptions.add(new SymbolRedeclarationException(msg, procHeading));
        }

        pushTable(); // Enter symbol table of procedure

        DefaultAST paramPart = procHeading.findFirst(Algol60Parser.PARAM_PART);
        DefaultAST valuePart = procHeading.findFirst(Algol60Parser.VALUE_PART);
        DefaultAST specPart = procHeading.findFirst(Algol60Parser.SPEC_PART);

        // Collect procedure parameters in order
        List<String> paramNames = new ArrayList<>();
        List<DefaultAST> paramTrees = new ArrayList<>();
        if (paramPart != null) {
            for (DefaultAST idf : paramPart.getChild(0)) {
                paramNames.add(idf.getText());
                paramTrees.add(idf);
            }
        }

        // Collect types and corresponding names and trees
        List<Type> specTypes = new ArrayList<>();
        List<String> specNames = new ArrayList<>();
        List<DefaultAST> specTrees = new ArrayList<>();
        if (specPart != null) {
            for (DefaultAST argType : specPart) {
                Type parameterType = Type.fromString(argType.getChild(0).getText());
                for (DefaultAST idf : argType.getChild(1)) {
                    String parameterName = idf.getText();
                    specNames.add(parameterName);
                    specTypes.add(parameterType);
                    specTrees.add(idf);
                }
            }
        }

        // Check value part
        List<String> valueNames = new ArrayList<>();
        if (valuePart != null) {
            for (DefaultAST idf : valuePart.getChild(0)) {
                String name = idf.getText();
                if (!paramNames.contains(name)) {
                    String msg =
                            String.format(
                                    "Value '%s' is not a parameter of procedure '%s'",
                                    name, procName);
                    exceptions.add(new ParameterMismatchException(msg, idf));
                } else {
                    valueNames.add(idf.getText());
                }
            }
        }

        // Check specification part and define parameters
        List<Type> orderedTypes = new ArrayList<>();
        for (String name : paramNames) {
            int index = specNames.indexOf(name);
            if (index == -1) {
                String msg =
                        String.format(
                                "Parameter '%s' has no type specified in "
                                        + "specification part of procedure '%s'",
                                name, procName);
                exceptions.add(
                        new ParameterMismatchException(
                                msg, paramTrees.get(paramNames.indexOf(name))));
            } else {
                Type type = specTypes.get(index);
                Parameter parameter = new Parameter(name, type);
                if (!valueNames.contains(name)) parameter.setByValue(false);
                orderedTypes.add(type);
                currentSymbolTable.define(parameter);

                // Clear parameter from spec lists
                specNames.remove(index);
                specTypes.remove(index);
                specTrees.remove(index);
            }
        }

        // Check for orphan declarations in specification part
        for (String name : specNames) {
            String msg = String.format("'%s' is not a parameter of procedure '%s'", name, procName);
            DefaultAST tree = specTrees.get(specNames.indexOf(name));
            exceptions.add(new ParameterMismatchException(msg, tree));
        }

        // Define procedure
        Procedure procedure = new Procedure(procName, procType, orderedTypes);
        procedure.setSymbolTable(currentSymbolTable);
        baseSymbolTable.define(procedure);

        // Define fictive result variable
        if (procType != Type.VOID) {
            Variable returnValue = new Variable(procName, procType);
            returnValue.setResultValue(true);
            currentSymbolTable.define(returnValue);
        }

        // Semantic controls on the procedure's block statements
        for (DefaultAST t : block) {
            try {
                t.accept(this);
            } catch (SemanticException e) {
                exceptions.add(e);
            }
        }

        if (procType != Type.VOID) {
            // Last statement of the block should be an assignment to the name of the procedure
            if (block.getChild(block.getChildCount() - 1).getType() == Algol60Parser.ASSIGNMENT) {
                if (!block.getChild(block.getChildCount() - 1)
                        .getChild(0)
                        .getText()
                        .equals(procName)) {
                    exceptions.add(
                            new MissingReturnException(
                                    "Procedure has no return statement ", block));
                }
            } else {
                exceptions.add(
                        new MissingReturnException("Procedure has no return statement", block));
            }
        }

        popTable(); // Quit symbol table of procedure

        return procType;
    }

    @Override
    public Type visit(ProcCallAST ast) {
        String procCallName = ast.getChild(0).getText();
        Symbol s = currentSymbolTable.resolve(procCallName);
        if (s == null) {
            throw new SymbolNotDeclaredException(
                    String.format("Procedure '%s' is not declared", procCallName), ast);
        }
        ArrayList<Type> types = new ArrayList<>();
        for (DefaultAST param : ast.getChild(1)) {
            if (param.getType() == Algol60Parser.IDENTIFIER) {
                Symbol parameter = currentSymbolTable.resolve(param.getText());
                if (parameter == null) {
                    throw new SymbolNotDeclaredException(
                            String.format("Parameter '%s' is not declared", param.getText()),
                            param);
                }
                types.add(parameter.getType());
            } else {
                types.add(param.accept(this));
            }
        }

        Procedure p = (Procedure) s;
        if (p.getParameterTypes().size() != types.size()) {
            throw new ParameterMismatchException(
                    String.format(
                            "Procedure '%s' expects %d parameters but received %d",
                            procCallName, p.getParameterTypes().size(), types.size()),
                    ast);
        }

        for (int i = 0; i < p.getParameterTypes().size(); i++) {
            Type paramType = p.getParameterTypes().get(i);
            Type actualType = types.get(i);
            if (Types.cannotAssign(paramType, actualType)) {
                DefaultAST paramAST = ast.getChild(1).getChild(i);
                throw new TypeMismatchException(
                        String.format(
                                "Parameter #%d of procedure '%s' expects %s but it received %s",
                                i + 1,
                                procCallName,
                                paramType.withPronoun(),
                                actualType.withPronoun()),
                        paramAST);
            }
        }

        return s.getType();
    }

    @Override
    public Type visit(IfStatementAST ast) {
        DefaultAST ifDef = ast.findFirst(Algol60Parser.IF_DEF);
        DefaultAST operator = ifDef.getChild(0);
        DefaultAST firstOperand = operator.getChild(0);
        DefaultAST secondOperand = operator.getChild(1);
        Type leftType, rightType;
        String leftName, rightName;
        if (firstOperand.getType() == Algol60Parser.IDENTIFIER) {
            Symbol s = currentSymbolTable.resolve(firstOperand.getText());
            if (s == null) {
                throw new SymbolNotDeclaredException(
                        String.format("'%s' is not declared", firstOperand.getText()),
                        firstOperand);
            }
            leftType = s.getType();
            leftName = s.getIdentifier();
        } else {
            leftType = firstOperand.accept(this);
            leftName = firstOperand.getChild(0).getText();
        }
        if (secondOperand.getType() == Algol60Parser.IDENTIFIER) {
            Symbol s = currentSymbolTable.resolve(secondOperand.getText());
            if (s == null) {
                throw new SymbolNotDeclaredException(
                        String.format("'%s' is not declared", secondOperand.getText()),
                        secondOperand);
            }
            rightType = s.getType();
            rightName = s.getIdentifier();
        } else {
            rightType = secondOperand.accept(this);
            rightName = secondOperand.getChild(0).getText();
        }
        if (operator.getChildCount() == 2) {
            if (rightType == leftType) {
                if (rightType == Type.STRING) {
                    throw new TypeMismatchException(
                            String.format(
                                    "cannot compare strings '%s' and '%s'", leftName, rightName),
                            firstOperand);
                }
            } else {
                throw new TypeMismatchException(
                        String.format(
                                "'%s' and '%s' have different types ('%s' and '%s')",
                                leftName, rightName, leftType, rightType),
                        firstOperand);
            }
        }
        DefaultAST thenDef = ast.findFirst(Algol60Parser.THEN_DEF);
        thenDef.accept(this);
        DefaultAST elseDef = ast.findFirst(Algol60Parser.ELSE_DEF);

        if (elseDef != null) {
            elseDef.accept(this);
        }

        return Type.VOID;
    }

    @Override
    public Type visit(ForClauseAST ast) {
        return Type.VOID;
    }

    @Override
    public Type visit(WhileClauseAST ast) {
        return Type.VOID;
    }

    @Override
    public Type visit(AssignmentAST ast) {
        String leftName = ast.getChild(0).getText();
        Symbol leftSymbol = currentSymbolTable.resolve(leftName);
        if (leftSymbol == null) {
            throw new SymbolNotDeclaredException(
                    String.format("'%s' is not declared", leftName), ast);
        }
        if (!leftSymbol.getKind().isAssignable()) {
            throw new TypeMismatchException(
                    String.format(
                            "Cannot assign to '%s' which is %s",
                            leftName, leftSymbol.getKind().withPronoun()),
                    ast);
        }

        Type rightType = null;
        if (ast.getChild(1).getType() == Algol60Parser.IDENTIFIER) {
            String rightName = ast.getChild(1).getText();
            Symbol rightSymbol = currentSymbolTable.resolve(rightName);
            if (rightSymbol == null) {
                throw new SymbolNotDeclaredException(
                        String.format("Variable '%s' is not declared.", rightName), ast);
            }
            rightType = rightSymbol.getType();

        } else {
            rightType = ast.getChild(1).accept(this);
        }
        if (Types.cannotAssign(leftSymbol.getType(), rightType)) {
            throw new TypeMismatchException(
                    String.format(
                            "Cannot assign %s to '%s' of type %s",
                            rightType.withPronoun(), leftName, leftSymbol.getType()),
                    ast.getChild(1));
        }
        return Type.VOID;
    }

    @Override
    public Type visit(ArrayDecAST ast) {
        return Type.VOID;
    }

    @Override
    public Type visit(ArrayAssignmentAST ast) {
        return Type.VOID;
    }

    @Override
    public Type visit(MultAST ast) {
        DefaultAST leftPart = ast.getChild(0);
        DefaultAST rightPart = ast.getChild(1);
        Type leftType = getType(leftPart);
        Type rightType = getType(rightPart);

        if (!typeCompat.get(leftType).contains(rightType)) {
            throw new TypeMismatchException(String.format("Operands types don't match."), ast);
        }
        if (leftType == Type.REAL || rightType == Type.REAL) {
            return Type.REAL;
        } else if (leftType == Type.INTEGER && rightType == Type.INTEGER) {
            return Type.INTEGER;
        }

        return Type.VOID;
    }

    @Override
    public Type visit(DivAST ast) {
        DefaultAST leftPart = ast.getChild(0);
        DefaultAST rightPart = ast.getChild(1);
        Type leftType = getType(leftPart);
        Type rightType = getType(rightPart);

        if (!typeCompat.get(leftType).contains(rightType)) {
            throw new TypeMismatchException(String.format("Operands types don't match."), ast);
        }

        return Type.REAL;
    }

    @Override
    public Type visit(ArrayCallAST ast) {
        return Type.VOID;
    }

    @Override
    public Type visit(IntAST ast) {
        return Type.INTEGER;
    }

    @Override
    public Type visit(Pow10AST ast) {
        return Type.REAL;
    }

    @Override
    public Type visit(PowAST ast) {
        DefaultAST leftPart = ast.getChild(0);
        DefaultAST rightPart = ast.getChild(1);
        Type leftType = getType(leftPart);
        Type rightType = getType(rightPart);

        if (!typeCompat.get(leftType).contains(rightType)) {
            throw new TypeMismatchException(String.format("Operands types don't match."), ast);
        }
        switch (leftType) {
            case REAL:
                switch (rightType) {
                    case REAL:
                    case INTEGER:
                        return Type.REAL;
                    default:
                        return Type.VOID;
                }
            case INTEGER:
                switch (rightType) {
                    case REAL:
                        return Type.REAL;
                    case INTEGER:
                        return Type.INTEGER;
                    default:
                        return Type.VOID;
                }
            default:
                return Type.VOID;
        }
    }

    @Override
    public Type visit(RealAST ast) {
        return Type.REAL;
    }

    @Override
    public Type visit(StrAST ast) {
        return Type.STRING;
    }

    @Override
    public Type visit(IntDivAST ast) {
        DefaultAST leftPart = ast.getChild(0);
        DefaultAST rightPart = ast.getChild(1);
        Type leftType = getType(leftPart);
        Type rightType = getType(rightPart);

        if (!typeCompat.get(leftType).contains(rightType)) {
            throw new TypeMismatchException(String.format("Operands types don't match."), ast);
        }

        return Type.INTEGER;
    }

    @Override
    public Type visit(AddAST ast) {

        DefaultAST leftPart = ast.getChild(0);
        DefaultAST rightPart = ast.getChild(1);
        Type leftType = getType(leftPart);
        Type rightType = getType(rightPart);

        if (!typeCompat.get(leftType).contains(rightType)) {
            throw new TypeMismatchException(String.format("Operands types don't match."), ast);
        }

        if (leftType == Type.REAL || rightType == Type.REAL) {
            return Type.REAL;
        } else if (leftType == Type.INTEGER && rightType == Type.INTEGER) {
            return Type.INTEGER;
        }
        return Type.VOID;
    }

    private Type getType(DefaultAST part) throws SemanticException {
        if (part.getType() == Algol60Parser.IDENTIFIER) {
            Symbol symbol = currentSymbolTable.resolve(part.getText());
            if (symbol == null) {
                throw new SymbolNotDeclaredException(
                        String.format("Variable %s not declared.", part.getText()), part);
            }
            return symbol.getType();
        }
        return part.accept(this);
    }

    @Override
    public Type visit(MinusAST ast) {
        DefaultAST leftPart = ast.getChild(0);
        DefaultAST rightPart = ast.getChild(1);
        Type leftType = getType(leftPart);
        Type rightType = getType(rightPart);

        if (!typeCompat.get(leftType).contains(rightType)) {
            throw new TypeMismatchException(String.format("Operands types don't match."), ast);
        }
        if (leftType == Type.REAL || rightType == Type.REAL) {
            return Type.REAL;
        } else if (leftType == Type.INTEGER && rightType == Type.INTEGER) {
            return Type.INTEGER;
        }

        return Type.VOID;
    }

    @Override
    public Type visit(LabelDecAST ast) {
        String name = ast.getChild(0).getText();

        if (currentSymbolTable.isDeclaredInScope(name)) {
            throw new SymbolRedeclarationException(
                    String.format("Label '%s' already declared in scope", name), ast);
        }

        Label label = new Label(name);
        currentSymbolTable.define(label);
        declaredLabels.add(label);

        return Type.VOID;
    }

    @Override
    public Type visit(GoToAST ast) {
        String name = ast.getChild(0).getText();
        // We don't know yet the scope of this label
        undeclaredLabels.add(new UndeclaredLabel(name, ast));
        return Type.VOID;
    }
}
