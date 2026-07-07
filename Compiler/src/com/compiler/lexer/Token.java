package com.compiler.lexer;

public class Token {
    public Tipo tipo;
    public String lexema;
    public int linha, col;

    public Token(Tipo t, String l, int ln, int cl) {
        tipo = t;
        lexema = l;
        linha = ln;
        col = cl;
    }

    public String toString() {
        return String.format("Token[%-20s | '%s' | %d:%d]", tipo, lexema, linha, col);
    }
}