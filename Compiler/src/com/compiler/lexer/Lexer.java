package com.compiler.lexer;

import java.util.ArrayList;
import java.util.List;

public class Lexer {

    private static final String[] keywordsArray = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while"
    };

    private static final String[] literalsArray = { "true", "false", "null" };

    private enum Estado {
        Q0, Q1, Q2, Q3, Q4, Q5, Q6, Q7, Q8, Q9, Q10, Q11, Q12, Q13, Q14, Q15,
        Q16, Q17, Q18, Q19, Q20, Q21, Q22, Q23, Q24, Q25, Q26, Q27, Q28, Q29,
        Q30, Q31, Q32, Q33, Q34, Q35, Q36, Q37, Q38, Q39, Q40, Q41, Q42, Q43,
        Q44, Q45, Q46, Q47, Q48, Q49, Q58, Q59, Q60, Q61, Q62, Q63
    }

    private final String src;
    private int pos;
    private int linha;
    private int col;
    private Estado estadoAtual;
    private StringBuilder lexemaBuffer;
    private int tokenStartLine;
    private int tokenStartCol;

    public Lexer(String src) {
        this.src = src;
        this.pos = 0;
        this.linha = 1;
        this.col = 1;
        this.estadoAtual = Estado.Q0;
        this.lexemaBuffer = new StringBuilder();
    }

    private boolean isKeyword(String word) {
        for (String kw : keywordsArray) {
            if (kw.equals(word))
                return true;
        }
        return false;
    }

    private boolean isLiteral(String word) {
        for (String lit : literalsArray) {
            if (lit.equals(word))
                return true;
        }
        return false;
    }

    public List<Token> scan() {
        List<Token> tokens = new ArrayList<>();

        while (pos < src.length()) {
            pularWhitespace();
            if (pos >= src.length())
                break;

            estadoAtual = Estado.Q0;
            lexemaBuffer = new StringBuilder();
            tokenStartLine = linha;
            tokenStartCol = col;

            Token t = processarToken();
            if (t != null && t.tipo != Tipo.DESCONHECIDO) {
                tokens.add(t);
            } else if (t != null && t.tipo == Tipo.DESCONHECIDO && t.lexema.length() > 0) {
                System.err.printf("[AVISO] Caractere não reconhecido: '%s' (linha %d, coluna %d)%n",
                        t.lexema, t.linha, t.col);
            }
        }

        tokens.add(new Token(Tipo.EOF, "", linha, col));
        return tokens;
    }

    private void pularWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                linha++;
                col = 1;
                pos++;
            } else if (c == ' ' || c == '\t' || c == '\r') {
                col++;
                pos++;
            } else {
                break;
            }
        }
    }

    private Token processarToken() {
        while (pos < src.length()) {
            char c = src.charAt(pos);

            if (estadoAtual == Estado.Q0) {
                return processarPrimeiroCaractere(c);
            }

            Estado proximo = transicao(estadoAtual, c);

            if (proximo == null) {
                return finalizarToken();
            }

            if (isEstadoFinal(proximo)) {
                lexemaBuffer.append(c);
                pos++;
                col++;
                return finalizarTokenComEstado(proximo);
            }

            lexemaBuffer.append(c);
            pos++;
            col++;
            estadoAtual = proximo;
        }

        return finalizarToken();
    }

    private Token processarPrimeiroCaractere(char c) {
        lexemaBuffer.append(c);
        pos++;
        col++;

        if (c == '/') {
            if (pos < src.length() && src.charAt(pos) == '/') {
                pularComentarioLinha();
                return null;
            }
            if (pos < src.length() && src.charAt(pos) == '*') {
                pularComentarioBloco();
                return null;
            }
        }

        if (c == '"') {
            estadoAtual = Estado.Q47;
            return processarString();
        }
        if (c == '\'') {
            return processarChar();
        }
        if (Character.isDigit(c)) {
            estadoAtual = Estado.Q1;
            return processarNumero();
        }
        if (Character.isLetter(c) || c == '_' || c == '$') {
            estadoAtual = Estado.Q8;
            return processarIdentificador();
        }

        // Tratamento específico para cada operador
        // Isso evita o problema do estado Q6 genérico
        return processarOperadorEspecifico(c);
    }

    private Token processarOperadorEspecifico(char primeiro) {
        // Se não há próximo caractere, retorna o operador simples
        if (pos >= src.length()) {
            return mapearOperador(String.valueOf(primeiro));
        }

        char segundo = src.charAt(pos);
        String lex = String.valueOf(primeiro);

        // Verifica combinações de 2 caracteres
        switch (primeiro) {
            case '=':
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_IGUAL, "==", tokenStartLine, tokenStartCol);
                }
                break;
            case '<':
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_MENOR_IGUAL, "<=", tokenStartLine, tokenStartCol);
                }
                if (segundo == '<') {
                    pos++;
                    col++;
                    lex += segundo;
                    // Verifica <<=
                    if (pos < src.length() && src.charAt(pos) == '=') {
                        pos++;
                        col++;
                        return new Token(Tipo.OP_SHIFT_LEFT_IGUAL, "<<=", tokenStartLine, tokenStartCol);
                    }
                    return new Token(Tipo.OP_SHIFT_LEFT, "<<", tokenStartLine, tokenStartCol);
                }
                break;
            case '>':
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_MAIOR_IGUAL, ">=", tokenStartLine, tokenStartCol);
                }
                if (segundo == '>') {
                    pos++;
                    col++;
                    lex += segundo;
                    if (pos < src.length() && src.charAt(pos) == '>') {
                        pos++;
                        col++;
                        lex += '>';
                        if (pos < src.length() && src.charAt(pos) == '=') {
                            pos++;
                            col++;
                            return new Token(Tipo.OP_SHIFT_UNSIGNED_IGUAL, ">>>=", tokenStartLine, tokenStartCol);
                        }
                        return new Token(Tipo.OP_SHIFT_UNSIGNED, ">>>", tokenStartLine, tokenStartCol);
                    }
                    if (pos < src.length() && src.charAt(pos) == '=') {
                        pos++;
                        col++;
                        return new Token(Tipo.OP_SHIFT_RIGHT_IGUAL, ">>=", tokenStartLine, tokenStartCol);
                    }
                    return new Token(Tipo.OP_SHIFT_RIGHT, ">>", tokenStartLine, tokenStartCol);
                }
                break;
            case '!':
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_DIFERENTE, "!=", tokenStartLine, tokenStartCol);
                }
                break;
            case '+':
                if (segundo == '+') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_INCREMENTO, "++", tokenStartLine, tokenStartCol);
                }
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_MAIS_IGUAL, "+=", tokenStartLine, tokenStartCol);
                }
                break;
            case '-':
                if (segundo == '-') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_DECREMENTO, "--", tokenStartLine, tokenStartCol);
                }
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_MENOS_IGUAL, "-=", tokenStartLine, tokenStartCol);
                }
                break;
            case '*':
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_VEZES_IGUAL, "*=", tokenStartLine, tokenStartCol);
                }
                break;
            case '/':
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_DIVIDIR_IGUAL, "/=", tokenStartLine, tokenStartCol);
                }
                break;
            case '%':
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_MODULO_IGUAL, "%=", tokenStartLine, tokenStartCol);
                }
                break;
            case '&':
                if (segundo == '&') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_E_LOGICO, "&&", tokenStartLine, tokenStartCol);
                }
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_E_IGUAL, "&=", tokenStartLine, tokenStartCol);
                }
                break;
            case '|':
                if (segundo == '|') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_OU_LOGICO, "||", tokenStartLine, tokenStartCol);
                }
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_OU_IGUAL, "|=", tokenStartLine, tokenStartCol);
                }
                break;
            case '^':
                if (segundo == '=') {
                    pos++;
                    col++;
                    return new Token(Tipo.OP_XOR_IGUAL, "^=", tokenStartLine, tokenStartCol);
                }
                break;
            case '.':
                if (segundo == '.') {
                    pos++;
                    col++;
                    if (pos < src.length() && src.charAt(pos) == '.') {
                        pos++;
                        col++;
                        return new Token(Tipo.OP_ELLIPSIS, "...", tokenStartLine, tokenStartCol);
                    }
                    // ".." não é um operador válido em Java, mas vamos tratar
                    return new Token(Tipo.DESCONHECIDO, "..", tokenStartLine, tokenStartCol);
                }
                break;
        }

        // Se não encontrou combinação, retorna o operador simples
        return mapearOperador(String.valueOf(primeiro));
    }

    private void pularComentarioLinha() {
        pos++;
        col++;

        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                break;
            }
            pos++;
            col++;
        }
    }

    private void pularComentarioBloco() {
        pos++;
        col++;

        int nestedLevel = 1;

        while (pos < src.length()) {
            char c = src.charAt(pos);

            if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                nestedLevel++;
                pos += 2;
                col += 2;
            } else if (c == '*' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                nestedLevel--;
                pos += 2;
                col += 2;
                if (nestedLevel == 0) {
                    return;
                }
            } else if (c == '\n') {
                linha++;
                col = 1;
                pos++;
            } else {
                pos++;
                col++;
            }
        }

        throw new LexError("Comentário de bloco não fechado", tokenStartLine, tokenStartCol);
    }

    private Token processarNumero() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            Estado prox = transicao(estadoAtual, c);

            if (prox == null) {
                return finalizarToken();
            }

            if (isEstadoFinal(prox)) {
                lexemaBuffer.append(c);
                pos++;
                col++;
                return finalizarTokenComEstado(prox);
            }

            lexemaBuffer.append(c);
            pos++;
            col++;
            estadoAtual = prox;
        }
        return finalizarToken();
    }

    private Token processarIdentificador() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            Estado prox = transicao(estadoAtual, c);

            if (prox == null) {
                return finalizarToken();
            }

            if (isEstadoFinal(prox)) {
                if (prox == Estado.Q7) {
                    return finalizarToken();
                }
                lexemaBuffer.append(c);
                pos++;
                col++;
                return finalizarTokenComEstado(prox);
            }

            lexemaBuffer.append(c);
            pos++;
            col++;
            estadoAtual = prox;
        }
        return finalizarToken();
    }

    private Token processarString() {
        while (pos < src.length()) {
            char c = src.charAt(pos);

            if (c == '"') {
                lexemaBuffer.append(c);
                pos++;
                col++;
                return new Token(Tipo.LITERAL_TEXTO, lexemaBuffer.toString(), tokenStartLine, tokenStartCol);
            }

            if (c == '\\') {
                lexemaBuffer.append(c);
                pos++;
                col++;
                if (pos < src.length()) {
                    lexemaBuffer.append(src.charAt(pos));
                    pos++;
                    col++;
                }
                continue;
            }

            if (c == '\n') {
                throw new LexError("String não fechada", tokenStartLine, tokenStartCol);
            }

            lexemaBuffer.append(c);
            pos++;
            col++;
        }

        throw new LexError("String não fechada", tokenStartLine, tokenStartCol);
    }

    private Token processarChar() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            lexemaBuffer.append(c);
            pos++;
            col++;

            if (c == '\n') {
                throw new LexError("Literal de caractere não fechado", tokenStartLine, tokenStartCol);
            }

            if (c == '\'' && lexemaBuffer.length() > 1) {
                return new Token(Tipo.LITERAL_CARACTERE, lexemaBuffer.toString(), tokenStartLine, tokenStartCol);
            }
        }

        throw new LexError("Literal de caractere não fechado", tokenStartLine, tokenStartCol);
    }

    private Estado transicao(Estado estado, char c) {
        switch (estado) {
            case Q0:
                if (Character.isLetter(c) || c == '_' || c == '$')
                    return Estado.Q8;
                if (Character.isDigit(c))
                    return Estado.Q1;
                if (c == '"')
                    return Estado.Q47;
                if (c == '+' || c == '-' || c == '*' || c == '%' ||
                        c == '<' || c == '>' || c == '=' || c == '!' ||
                        c == '&' || c == '|' || c == '^' || c == '~' ||
                        c == '?' || c == ':' || c == '.' || c == '(' ||
                        c == ')' || c == '{' || c == '}' || c == '[' ||
                        c == ']' || c == ';' || c == ',' || c == '@') {
                    return Estado.Q6;
                }
                if (c == '/')
                    return Estado.Q16;
                return null;

            case Q1:
                if (Character.isDigit(c))
                    return Estado.Q1;
                if (c == '.')
                    return Estado.Q3;
                if (c == 'l' || c == 'L')
                    return Estado.Q2;
                if (c == 'f' || c == 'F')
                    return Estado.Q2;
                if (c == 'd' || c == 'D')
                    return Estado.Q2;
                return null;

            case Q2:
                return null;

            case Q3:
                if (Character.isDigit(c))
                    return Estado.Q4;
                return null;

            case Q4:
                if (Character.isDigit(c))
                    return Estado.Q4;
                if (c == 'f' || c == 'F')
                    return Estado.Q5;
                if (c == 'd' || c == 'D')
                    return Estado.Q5;
                return null;

            case Q5:
                return null;

            case Q6:
                if (c == '=')
                    return Estado.Q10;
                if (c == '+')
                    return Estado.Q11;
                if (c == '-')
                    return Estado.Q13;
                if (c == '<')
                    return Estado.Q58;
                if (c == '>')
                    return Estado.Q59;
                if (c == '&')
                    return Estado.Q36;
                if (c == '|')
                    return Estado.Q37;
                if (c == '.')
                    return Estado.Q40;
                return null;

            case Q8:
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$')
                    return Estado.Q8;
                if (c == ' ')
                    return Estado.Q7;
                return null;

            case Q7:
                return null;

            case Q10:
                return null;

            case Q11:
                if (c == '+')
                    return Estado.Q12;
                return null;

            case Q12:
                return null;

            case Q13:
                if (c == '-')
                    return Estado.Q14;
                if (c == '=')
                    return Estado.Q15;
                return null;

            case Q14:
                return null;

            case Q15:
                return null;

            case Q16:
                if (c == '/')
                    return Estado.Q17;
                if (c == '*')
                    return Estado.Q46;
                if (c == '=')
                    return Estado.Q19;
                return null;

            case Q17:
                if (c == '\n')
                    return null;
                return Estado.Q17;

            case Q19:
                return null;

            case Q36:
                if (c == '&')
                    return Estado.Q38;
                if (c == '=')
                    return Estado.Q43;
                return null;

            case Q37:
                if (c == '|')
                    return Estado.Q41;
                if (c == '=')
                    return Estado.Q42;
                return null;

            case Q38:
                return null;

            case Q40:
                if (c == '.')
                    return Estado.Q39;
                return null;

            case Q39:
                return null;

            case Q41:
                return null;

            case Q42:
                return null;

            case Q43:
                return null;

            case Q46:
                if (c == '*')
                    return Estado.Q44;
                return Estado.Q46;

            case Q44:
                if (c == '/')
                    return Estado.Q45;
                return Estado.Q46;

            case Q45:
                return Estado.Q0;

            case Q47:
                if (c == '"')
                    return Estado.Q48;
                if (c == '\\')
                    return Estado.Q49;
                return Estado.Q47;

            case Q48:
                return null;

            case Q49:
                return Estado.Q47;

            case Q58:
                if (c == '=')
                    return Estado.Q60;
                if (c == '<')
                    return Estado.Q58;
                return null;

            case Q59:
                if (c == '=')
                    return Estado.Q61;
                if (c == '>')
                    return Estado.Q62;
                return null;

            case Q60:
                return null;

            case Q61:
                return null;

            case Q62:
                if (c == '=')
                    return Estado.Q63;
                return null;

            case Q63:
                return null;

            default:
                return null;
        }
    }

    private boolean isEstadoFinal(Estado estado) {
        switch (estado) {
            case Q2:
            case Q5:
            case Q7:
            case Q10:
            case Q12:
            case Q13:
            case Q14:
            case Q15:
            case Q19:
            case Q20:
            case Q21:
            case Q23:
            case Q24:
            case Q26:
            case Q28:
            case Q29:
            case Q30:
            case Q32:
            case Q33:
            case Q35:
            case Q36:
            case Q38:
            case Q39:
            case Q40:
            case Q43:
            case Q48:
            case Q58:
            case Q59:
            case Q60:
            case Q61:
            case Q62:
            case Q63:
                return true;
            default:
                return false;
        }
    }

    private Token finalizarToken() {
        String lex = lexemaBuffer.toString();

        if (lex.isEmpty()) {
            return null;
        }

        if (estadoAtual == Estado.Q8) {
            if (isKeyword(lex))
                return new Token(Tipo.PALAVRA_RESERVADA, lex, tokenStartLine, tokenStartCol);
            if (isLiteral(lex))
                return new Token(Tipo.LITERAL_ESPECIAL, lex, tokenStartLine, tokenStartCol);
            return new Token(Tipo.IDENTIFICADOR, lex, tokenStartLine, tokenStartCol);
        }

        if (estadoAtual == Estado.Q1 || estadoAtual == Estado.Q2) {
            if (lex.endsWith("l") || lex.endsWith("L")) {
                return new Token(Tipo.LITERAL_LONG, lex, tokenStartLine, tokenStartCol);
            }
            return new Token(Tipo.LITERAL_INTEIRO, lex, tokenStartLine, tokenStartCol);
        }

        if (estadoAtual == Estado.Q4 || estadoAtual == Estado.Q5) {
            if (lex.endsWith("f") || lex.endsWith("F")) {
                return new Token(Tipo.LITERAL_FLOAT, lex, tokenStartLine, tokenStartCol);
            }
            return new Token(Tipo.LITERAL_DOUBLE, lex, tokenStartLine, tokenStartCol);
        }

        return mapearOperador(lex);
    }

    private Token finalizarTokenComEstado(Estado estadoFinal) {
        String lex = lexemaBuffer.toString();

        switch (estadoFinal) {
            case Q2:
                if (lex.endsWith("l") || lex.endsWith("L")) {
                    return new Token(Tipo.LITERAL_LONG, lex, tokenStartLine, tokenStartCol);
                }
                return new Token(Tipo.LITERAL_INTEIRO, lex, tokenStartLine, tokenStartCol);

            case Q5:
                if (lex.endsWith("f") || lex.endsWith("F")) {
                    return new Token(Tipo.LITERAL_FLOAT, lex, tokenStartLine, tokenStartCol);
                }
                return new Token(Tipo.LITERAL_DOUBLE, lex, tokenStartLine, tokenStartCol);

            case Q10:
                return new Token(Tipo.OP_ATRIBUICAO, lex, tokenStartLine, tokenStartCol);
            case Q12:
                return new Token(Tipo.OP_INCREMENTO, lex, tokenStartLine, tokenStartCol);
            case Q13:
                return new Token(Tipo.OP_DECREMENTO, lex, tokenStartLine, tokenStartCol);
            case Q14:
                return new Token(Tipo.OP_MENOS_IGUAL, lex, tokenStartLine, tokenStartCol);
            case Q15:
                return new Token(Tipo.OP_DECREMENTO, lex, tokenStartLine, tokenStartCol);
            case Q19:
                return new Token(Tipo.OP_DIVIDIR_IGUAL, lex, tokenStartLine, tokenStartCol);
            case Q38:
                return new Token(Tipo.OP_E_LOGICO, lex, tokenStartLine, tokenStartCol);
            case Q39:
                return new Token(Tipo.OP_ELLIPSIS, lex, tokenStartLine, tokenStartCol);
            case Q40:
                return new Token(Tipo.PONTO, lex, tokenStartLine, tokenStartCol);
            case Q41:
                return new Token(Tipo.OP_OU_LOGICO, lex, tokenStartLine, tokenStartCol);
            case Q42:
                return new Token(Tipo.OP_OU_IGUAL, lex, tokenStartLine, tokenStartCol);
            case Q43:
                return new Token(Tipo.OP_E_IGUAL, lex, tokenStartLine, tokenStartCol);
            case Q48:
                return new Token(Tipo.LITERAL_TEXTO, lex, tokenStartLine, tokenStartCol);
            case Q58:
                return new Token(Tipo.OP_SHIFT_LEFT, lex, tokenStartLine, tokenStartCol);
            case Q59:
                return new Token(Tipo.OP_SHIFT_RIGHT, lex, tokenStartLine, tokenStartCol);
            case Q60:
                return new Token(Tipo.OP_SHIFT_LEFT_IGUAL, lex, tokenStartLine, tokenStartCol);
            case Q61:
                return new Token(Tipo.OP_SHIFT_RIGHT_IGUAL, lex, tokenStartLine, tokenStartCol);
            case Q62:
                return new Token(Tipo.OP_SHIFT_UNSIGNED, lex, tokenStartLine, tokenStartCol);
            case Q63:
                return new Token(Tipo.OP_SHIFT_UNSIGNED_IGUAL, lex, tokenStartLine, tokenStartCol);

            default:
                return mapearOperador(lex);
        }
    }

    private Token mapearOperador(String lex) {
        switch (lex) {
            case "+":
                return new Token(Tipo.OP_MAIS, lex, tokenStartLine, tokenStartCol);
            case "-":
                return new Token(Tipo.OP_MENOS, lex, tokenStartLine, tokenStartCol);
            case "*":
                return new Token(Tipo.OP_VEZES, lex, tokenStartLine, tokenStartCol);
            case "/":
                return new Token(Tipo.OP_DIVIDIR, lex, tokenStartLine, tokenStartCol);
            case "%":
                return new Token(Tipo.OP_MODULO, lex, tokenStartLine, tokenStartCol);
            case "=":
                return new Token(Tipo.OP_ATRIBUICAO, lex, tokenStartLine, tokenStartCol);
            case "==":
                return new Token(Tipo.OP_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "!=":
                return new Token(Tipo.OP_DIFERENTE, lex, tokenStartLine, tokenStartCol);
            case "<":
                return new Token(Tipo.OP_MENOR, lex, tokenStartLine, tokenStartCol);
            case ">":
                return new Token(Tipo.OP_MAIOR, lex, tokenStartLine, tokenStartCol);
            case "<=":
                return new Token(Tipo.OP_MENOR_IGUAL, lex, tokenStartLine, tokenStartCol);
            case ">=":
                return new Token(Tipo.OP_MAIOR_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "&&":
                return new Token(Tipo.OP_E_LOGICO, lex, tokenStartLine, tokenStartCol);
            case "||":
                return new Token(Tipo.OP_OU_LOGICO, lex, tokenStartLine, tokenStartCol);
            case "!":
                return new Token(Tipo.OP_NAO, lex, tokenStartLine, tokenStartCol);
            case "&":
                return new Token(Tipo.OP_E, lex, tokenStartLine, tokenStartCol);
            case "|":
                return new Token(Tipo.OP_OU, lex, tokenStartLine, tokenStartCol);
            case "^":
                return new Token(Tipo.OP_XOR, lex, tokenStartLine, tokenStartCol);
            case "~":
                return new Token(Tipo.OP_COMPLEMENTO, lex, tokenStartLine, tokenStartCol);
            case "++":
                return new Token(Tipo.OP_INCREMENTO, lex, tokenStartLine, tokenStartCol);
            case "--":
                return new Token(Tipo.OP_DECREMENTO, lex, tokenStartLine, tokenStartCol);
            case "+=":
                return new Token(Tipo.OP_MAIS_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "-=":
                return new Token(Tipo.OP_MENOS_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "*=":
                return new Token(Tipo.OP_VEZES_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "/=":
                return new Token(Tipo.OP_DIVIDIR_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "%=":
                return new Token(Tipo.OP_MODULO_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "&=":
                return new Token(Tipo.OP_E_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "|=":
                return new Token(Tipo.OP_OU_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "^=":
                return new Token(Tipo.OP_XOR_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "<<":
                return new Token(Tipo.OP_SHIFT_LEFT, lex, tokenStartLine, tokenStartCol);
            case ">>":
                return new Token(Tipo.OP_SHIFT_RIGHT, lex, tokenStartLine, tokenStartCol);
            case ">>>":
                return new Token(Tipo.OP_SHIFT_UNSIGNED, lex, tokenStartLine, tokenStartCol);
            case "<<=":
                return new Token(Tipo.OP_SHIFT_LEFT_IGUAL, lex, tokenStartLine, tokenStartCol);
            case ">>=":
                return new Token(Tipo.OP_SHIFT_RIGHT_IGUAL, lex, tokenStartLine, tokenStartCol);
            case ">>>=":
                return new Token(Tipo.OP_SHIFT_UNSIGNED_IGUAL, lex, tokenStartLine, tokenStartCol);
            case "(":
                return new Token(Tipo.ABRE_PARENTESE, lex, tokenStartLine, tokenStartCol);
            case ")":
                return new Token(Tipo.FECHA_PARENTESE, lex, tokenStartLine, tokenStartCol);
            case "{":
                return new Token(Tipo.ABRE_CHAVETA, lex, tokenStartLine, tokenStartCol);
            case "}":
                return new Token(Tipo.FECHA_CHAVETA, lex, tokenStartLine, tokenStartCol);
            case "[":
                return new Token(Tipo.ABRE_COLCHETE, lex, tokenStartLine, tokenStartCol);
            case "]":
                return new Token(Tipo.FECHA_COLCHETE, lex, tokenStartLine, tokenStartCol);
            case ";":
                return new Token(Tipo.PONTO_VIRGULA, lex, tokenStartLine, tokenStartCol);
            case ",":
                return new Token(Tipo.VIRGULA, lex, tokenStartLine, tokenStartCol);
            case ".":
                return new Token(Tipo.PONTO, lex, tokenStartLine, tokenStartCol);
            case ":":
                return new Token(Tipo.DOIS_PONTOS, lex, tokenStartLine, tokenStartCol);
            case "?":
                return new Token(Tipo.OP_TERNARIO, lex, tokenStartLine, tokenStartCol);
            case "...":
                return new Token(Tipo.OP_ELLIPSIS, lex, tokenStartLine, tokenStartCol);
            default:
                return new Token(Tipo.DESCONHECIDO, lex, tokenStartLine, tokenStartCol);
        }
    }
}