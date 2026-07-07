package com.compiler.lexer;

public class LexError extends RuntimeException {
    public LexError(String msg, int linha, int coluna) {
        super(String.format("[ERRO] linha %d, col %d -> %s", linha, coluna, msg));
    }
}