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

    public SemanticAnalysisVisitor(SymbolTable symbolTable) {
        if (symbolTable == null) {
            throw new IllegalArgumentException("Symbol table cannot be null");
        }
        this.currentSymbolTable = symbolTable;
        this.exceptions = new ArrayList<>();
        this.undeclaredLabels = new LinkedHashSet<>();
        this.declaredLabels = new LinkedHashSet<>();
    }

    public List<SemanticException> getExceptions() {
        return exceptions;
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

    @Override
    public Type visit(DefaultAST ast) {
        for (DefaultAST t : ast) {
            t.accept(this);
        }
        return Type.VOID;
    }

    @Override
    public Type visit(RootAST ast) {
        ast.getChildAST(0).accept(this);
        // Final operations
        checkLabelDeclarations();
        exceptions.sort(Comparator.comparingInt(SemanticException::getLine));
        return Type.VOID;
    }

    @Override
    public Type visit(BlockAST ast) {
        currentSymbolTable = currentSymbolTable.createChild();
        for (DefaultAST t : ast) {
            try {
                t.accept(this);
            } catch (SemanticException e) {
                exceptions.add(e);
            }
        }
        currentSymbolTable = currentSymbolTable.getParent();
        return Type.VOID;
    }

    @Override
    public Type visit(VarDecAST ast) {
        Type type = Type.fromString(ast.getChild(0).getText());
        DefaultAST idList = ast.getChildAST(1);

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
        SymbolTable procSymbolTable = currentSymbolTable;

        int n = ast.getChildCount();
        String procname = null;
        Procedure proc;
        List<Type> args = new ArrayList<Type>(); // liste de parametres
        List<Type> arg = new ArrayList<Type>(); // liste de paramentres dans l'ordre
        List<Symbol> variables = new ArrayList<>();

        DefaultAST procHeading = null;
        DefaultAST block = null;
        Type type = Type.VOID;
        if (n == 2) {
            procHeading = ast.getChildAST(0);
            block = ast.getChildAST(1);
        } else if (n == 3) {
            procHeading = ast.getChildAST(1);
            type = Type.fromString(ast.getChild(0).getText());
            block = ast.getChildAST(2);
        }
        procname = procHeading.getChild(0).getText();

        currentSymbolTable = currentSymbolTable.createChild();

        if (type != Type.VOID) {
            Symbol returnValue = new Variable(procname, type);
            currentSymbolTable.define(returnValue);
        }

        if (procHeading.getChildCount() > 2) {
            int nbre = procHeading.getChild(3).getChildCount();
            for (int i = 0; i < nbre; i++) {
                for (int j = 0;
                        j < procHeading.getChild(3).getChild(i).getChild(1).getChildCount();
                        j++) {
                    args.add(
                            Type.fromString(
                                    procHeading.getChild(3).getChild(i).getChild(0).getText()));
                    variables.add(
                            new Parameter(
                                    procHeading
                                            .getChild(3)
                                            .getChild(i)
                                            .getChild(1)
                                            .getChild(j)
                                            .toString(),
                                    Type.fromString(
                                            procHeading
                                                    .getChild(3)
                                                    .getChild(i)
                                                    .getChild(0)
                                                    .getText())));
                }
            }

            for (int u = 0; u < procHeading.getChild(1).getChild(0).getChildCount(); u++) {
                for (int i = 0; i < variables.size(); i++) {
                    if (procHeading
                            .getChild(1)
                            .getChild(0)
                            .getChild(u)
                            .getText()
                            .equals(variables.get(i).getIdentifier())) {
                        currentSymbolTable.define(variables.get(i));
                        arg.add(args.get(i));
                    }
                }
            }
        }

        proc = new Procedure(procname, type, arg);
        procSymbolTable.define(proc);

        for (DefaultAST t : block) {
            try {
                t.accept(this);
            } catch (SemanticException e) {
                exceptions.add(e);
            }
        }
        if (currentSymbolTable.isDeclaredInScope(procname)) {
            throw new SymbolRedeclarationException(
                    String.format("Procedure '%s' is already declared in scope", procname),
                    procHeading);
        }
        currentSymbolTable = currentSymbolTable.getParent();

        return Type.VOID;
    }

    @Override
    public Type visit(ProcCallAST ast) {
        return Type.VOID;
    }

    @Override
    public Type visit(IfStatementAST ast) {
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
                    String.format("Assignment %s not declared.", leftName), ast);
        }

        Type rightType = null;
        if (ast.getChildAST(1).getType() == Algol60Parser.IDENTIFIER) {
            String rightName = ast.getChild(1).getText();
            Symbol rightSymbol = currentSymbolTable.resolve(rightName);
            if (rightSymbol == null) {
                throw new SymbolNotDeclaredException(
                        String.format("Assignment %s not declared.", rightName), ast);
            }
            rightType = rightSymbol.getType();

        } else {
            rightType = ast.getChildAST(1).accept(this);
        }
        if (leftSymbol.getType() != rightType) {
            throw new TypeMismatchException(
                    String.format(
                            "Type mismatch: %s and %s in assignment",
                            leftName, ast.getChildAST(1).getText()),
                    ast);
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
        return Type.VOID;
    }

    @Override
    public Type visit(DivAST ast) {
        return Type.VOID;
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
        return Type.VOID;
    }

    @Override
    public Type visit(PowAST ast) {
        return Type.VOID;
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
        return Type.VOID;
    }

    @Override
    public Type visit(AddAST ast) {
        String leftPart = ast.getChildAST(0).getText();
        String rightPart = ast.getChildAST(1).getText();
        Symbol leftSymbol = currentSymbolTable.resolve(leftPart);
        Symbol rightSymbol = currentSymbolTable.resolve(rightPart);
        if (leftSymbol == null) {
            if (ast.getChildAST(0).getType() != Algol60Parser.STR) {
                if (rightSymbol == null) {
                    if (ast.getChildAST(1).getType() != Algol60Parser.STR) {
                        //
                    }
                }
            }
        }

        return Type.VOID;
    }

    @Override
    public Type visit(MinusAST ast) {
        return Type.VOID;
    }

    @Override
    public Type visit(TermAST ast) {
        return Type.VOID;
    }

    @Override
    public Type visit(LabelDecAST ast) {
        String name = ast.getChildAST(0).getText();

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
