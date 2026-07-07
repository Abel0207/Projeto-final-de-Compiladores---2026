package com.compiler.semantic;

public class SemanticError {
    public final int line;
    public final int col;
    public final String message;

    public SemanticError(int line, int col, String message) {
        this.line = line;
        this.col = col;
        this.message = message;
    }

    public String format() {
        return String.format("Erro semântico em %d:%d - %s", line, col, message);
    }

    @Override
    public String toString() {
        return format();
    }
}
