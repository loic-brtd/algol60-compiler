package eu.telecomnancy.symbols;

import eu.telecomnancy.ast.DefaultAST;

public class OrphanGoto {

    private DefaultAST tree;
    private SymbolTable symbolTable;
    private String identifier;

    public OrphanGoto(String idf, SymbolTable symbolTable, DefaultAST tree) {
        this.tree = tree;
        this.symbolTable = symbolTable;
        this.identifier = idf;
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    public DefaultAST getTree() {
        return tree;
    }

    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String toString() {
        return String.format("Orphan goto: %s (line %d)", identifier, tree.getLine());
    }
}
