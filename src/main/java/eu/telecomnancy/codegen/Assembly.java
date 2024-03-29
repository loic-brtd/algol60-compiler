package eu.telecomnancy.codegen;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

public class Assembly {
    public static final String INDENT = "    ";
    public static final String LINE_SEPARATOR = "\n";
    public static final String STRING_DEF_MARKER = "###_STRING_DEF_###";

    private StringBuilder normalLines;
    private StringBuilder constants;
    private StringBuilder procedures;
    private Stack<StringBuilder> procedureStack;
    private Mode mode;
    private Set<String> definedLabels = new HashSet<>();

    public Assembly() {
        normalLines = new StringBuilder();
        constants = new StringBuilder();
        procedures = new StringBuilder();
        procedureStack = new Stack<>();
        mode = Mode.NORMAL;
    }

    public boolean isLabelDefined(String label) {
        return definedLabels.contains(label);
    }

    public void push(String srcReg, String comment) {
        this.code("STW " + srcReg + ", -(SP)", comment);
    }

    public void push(String srcReg) {
        push(srcReg, "Push value of " + srcReg + " on stack");
    }

    public void pop(String dstReg, String comment) {
        this.code("LDW " + dstReg + ", (SP)+", comment);
    }

    public void pop(String dstReg) {
        pop(dstReg, "Pop stack value into " + dstReg);
    }

    public void beginProcedureDeclaration() {
        mode = Mode.PROC_DEC;
        procedureStack.push(new StringBuilder(LINE_SEPARATOR));
    }

    public void endProcedureDeclaration() {
        if (procedureStack.isEmpty()) {
            throw new IllegalStateException("Too many endProcedureDeclaration()");
        }
        procedures.append(procedureStack.pop());
        if (procedureStack.isEmpty()) {
            mode = Mode.NORMAL;
        }
    }

    public void code(String code, String comment) {
        StringBuilder b = getBuilder(mode);
        b.append(String.format("%-23s%s", INDENT + code, " // " + comment));
        b.append(LINE_SEPARATOR);
    }

    public void label(String label, String comment) {
        definedLabels.add(label);
        StringBuilder b = getBuilder(mode);
        b.append(LINE_SEPARATOR);
        b.append(label);
        b.append(" // ");
        b.append(comment);
        b.append(LINE_SEPARATOR);
    }

    public void comment(String code) {
        StringBuilder b = getBuilder(mode);
        b.append(LINE_SEPARATOR);
        b.append(INDENT);
        b.append("// ");
        b.append(code);
        b.append(LINE_SEPARATOR);
    }

    public void comment(String code, String comment) {
        StringBuilder b = getBuilder(mode);
        b.append(String.format("%-23s%s", INDENT + "// " + code, " // " + comment));
        b.append(LINE_SEPARATOR);
    }

    private Set<String> stringConstants = new HashSet<>();

    public void string(String constant, String value) {
        if (stringConstants.contains(constant)) return;
        stringConstants.add(constant);
        constants.append(INDENT);
        String quotedStr = '"' + value + '"';
        String pattern = "%-12s string  %-8s";
        constants.append(String.format(pattern, constant, quotedStr));
        constants.append(LINE_SEPARATOR);
    }

    public void byteDef(String constant, int value) {
        constants.append(INDENT);
        String pattern = "%-12s byte    %-8d";
        constants.append(String.format(pattern, constant, value));
        constants.append(LINE_SEPARATOR);
    }

    public void putStringDefinitionsHere() {
        normalLines.append(STRING_DEF_MARKER);
        normalLines.append(LINE_SEPARATOR);
    }

    public void equ(String constant, String value, String comment) {
        normalLines.append(INDENT);
        normalLines.append(
                String.format("%-12s equ     %-8s %s", constant, value, "// " + comment));
        normalLines.append(LINE_SEPARATOR);
    }

    public void def(String key, String value, String comment) {
        normalLines.append(INDENT);
        normalLines.append(String.format("%-12s %-16s %s", key, value, "// " + comment));
        normalLines.append(LINE_SEPARATOR);
    }

    public void newline() {
        normalLines.append(LINE_SEPARATOR);
    }

    public void insert(String code) {
        StringBuilder b = getBuilder(mode);
        b.append(code);
    }

    public void newEnvironment() {
        this.comment("Prepare environment");
        this.code("LDW R1, BP", "Save a copy of old base pointer in R1");
        this.code("STW BP, -(SP)", "Push old base pointer on stack (dynamic chaining)");
        this.code("LDW BP, SP", "New base pointer is the current stack pointer");
        this.code("STW R1, -(SP)", "Push old base pointer on stack (static chaining)");
    }

    public void newProcedureEnvironment() {
        this.comment("Prepare procedure environment");
        this.push("BP", "Push old BP on stack (dynamic chaining)");
        this.code("LDW BP, SP", "New BP is the current SP");
        this.push("R1", "Push SC computed by the caller (static chaining)");
    }

    public void endEnvironment() {
        this.comment("End environment");
        this.code("LDW SP, BP", "Return to base of current environment");
        this.code("LDW BP, (SP)+", "Pop previously saved base pointer (dynamic chaining)");
    }

    @Override
    public String toString() {
        return normalLines
                .toString()
                .replaceFirst(STRING_DEF_MARKER, constants.toString())
                .concat(procedures.toString());
    }

    public static Assembly exampleWrite(String str) {
        Assembly asm = new Assembly();

        asm.equ("EXIT_EXC", "64", "n° d'exceptoin de EXIT");
        asm.equ("READ_EXC", "65", "n° d'exception de READ (lit 1 ligne)");
        asm.equ("WRITE_EXC", "66", "n° d'exception de WRITE (affiche 1 ligne)");
        asm.equ("STACK_ADRS", "0x1000", "base de pile en 1000h (par exemple)");
        asm.equ("LOAD_ADRS", "0xF000", "adresse de chargement de l'exécutable");
        asm.equ("INT_SIZE", "4", "TAILLE D'UN INT");

        asm.newline();
        asm.equ("SP", "R15", "alias pour R15, pointeur de pile");
        asm.equ("WR", "R14", "Work Register (registre de travail)");
        asm.equ("BP", "R13", "frame Base Pointer (pointage environnement)");

        asm.newline();
        asm.def("ORG", "LOAD_ADRS", "adresse de chargement");
        asm.def("START", "main_", "adresse de démarrage");

        asm.newline();
        asm.string("VALUE", str);

        asm.label("main_", "début du programme");

        asm.code("LDW R0, #VALUE", "place le string dans R0");
        asm.code("LDQ WRITE_EXC, WR", "place la trappe WRITE dans WR");
        asm.code("TRP WR", "lance la trappe WRITE");

        //        asm.code("TRP #READ_EXC", "lance la trappe READ");
        //        asm.code("TRP #WRITE_EXC", "lance la trappe WRITE");

        asm.newline();
        asm.code("TRP #EXIT_EXC", "arrete le programme");
        return asm;
    }

    private enum Mode {
        NORMAL,
        PROC_DEC
    }

    private StringBuilder getBuilder(Mode mode) {
        switch (mode) {
            case NORMAL:
                return normalLines;
            case PROC_DEC:
                return procedureStack.peek();
            default:
                throw new IllegalStateException();
        }
    }
}
