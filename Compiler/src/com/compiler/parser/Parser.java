package com.compiler.parser;

import com.compiler.lexer.Lexer;
import com.compiler.lexer.Token;
import com.compiler.symbol.SymbolEntry;
import com.compiler.symbol.SymbolTable;
import com.compiler.lexer.Tipo;

import java.util.*;

public class Parser {

    private final List<Token> tokens;
    private int pos;
    private final List<ParseError> errors;
    private final List<DuplicateDeclaration> duplicateDeclarations;

    // Tabela de Símbolos
    private SymbolTable currentScope;
    private final SymbolTable globalScope;
    private String currentClassName;
    private int currentLine;
    private int currentColumn;

    // Construtor ÚNICO
    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
        this.errors = new ArrayList<>();
        this.duplicateDeclarations = new ArrayList<>();
        this.globalScope = new SymbolTable(null, "global");
        this.currentScope = globalScope;
        this.currentLine = 1;
        this.currentColumn = 1;
    }

    public static Program parseSource(String source) {
        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.scan();
        return new Parser(tokens).parseProgram();
    }

    // Getter para tabela de símbolos
    public SymbolTable getSymbolTable() {
        return globalScope;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        List<String> messages = new ArrayList<>();
        for (ParseError error : errors) {
            messages.add(error.getMessage());
        }
        return Collections.unmodifiableList(messages);
    }

    public List<DuplicateDeclaration> getDuplicateDeclarations() {
        return Collections.unmodifiableList(duplicateDeclarations);
    }

    public static class DuplicateDeclaration {
        public final String name;
        public final int line;
        public final int col;
        public final String scopePath;

        public DuplicateDeclaration(String name, int line, int col, String scopePath) {
            this.name = name;
            this.line = line;
            this.col = col;
            this.scopePath = scopePath;
        }
    }

    // Método auxiliar para adicionar símbolos
    private void addToSymbolTable(String name, SymbolEntry.SymbolKind kind,
            String typeName, int arrayDepth,
            String initialValue, List<String> modifiers) {
        SymbolEntry.DataType dataType;
        String customType = null;

        if (isPrimitiveTypeName(typeName)) {
            dataType = SymbolEntry.DataType.fromString(typeName);
        } else if (typeName.equals("String")) {
            dataType = SymbolEntry.DataType.STRING;
        } else if (typeName.equals("void")) {
            dataType = SymbolEntry.DataType.VOID;
        } else {
            dataType = SymbolEntry.DataType.OBJECT;
            customType = typeName;
        }

        int size;
        if (arrayDepth > 0) {
            size = 8;
        } else {
            size = dataType.getSize();
        }

        int address = currentScope.allocateAddress(size);

        SymbolEntry entry = new SymbolEntry(
                name, kind, dataType, customType, size, address,
                currentScope.getFullScopePath(), initialValue, modifiers,
                arrayDepth, currentLine, currentColumn);

        boolean added = currentScope.addSymbol(entry);
        if (!added) {
            duplicateDeclarations.add(new DuplicateDeclaration(
                    name, currentLine, currentColumn, currentScope.getFullScopePath()));
        }
    }

    private boolean isPrimitiveTypeName(String name) {
        return switch (name) {
            case "boolean", "byte", "short", "char", "int",
                    "long", "float", "double" ->
                true;
            default -> false;
        };
    }

    private String extractInitialValue(Expression expr) {
        if (expr instanceof LiteralExpression lit) {
            return lit.value;
        } else if (expr instanceof NewExpression) {
            return "new";
        } else if (expr instanceof IdentifierExpression id) {
            return id.name;
        }
        return "expressão";
    }

    private void updatePosition(Token token) {
        this.currentLine = token.linha;
        this.currentColumn = token.col;
    }

    private static <T extends Node> T mark(T node, Token token) {
        node.line = token.linha;
        node.col = token.col;
        return node;
    }

    private static <T extends Node> T markPos(T node, int line, int col) {
        node.line = line;
        node.col = col;
        return node;
    }

    public Program parseProgram() {
        Optional<PackageDeclaration> pkg = Optional.empty();
        if (matchKeyword("package")) {
            try {
                pkg = Optional.of(parsePackageDeclaration());
            } catch (ParseError error) {
                synchronizeTopLevel();
            }
        }

        List<ImportDeclaration> imports = new ArrayList<>();
        while (matchKeyword("import")) {
            try {
                imports.add(parseImportDeclaration());
            } catch (ParseError error) {
                synchronizeTopLevel();
            }
        }

        List<TypeDeclaration> types = new ArrayList<>();
        while (!isAtEnd()) {
            try {
                types.add(parseTypeDeclaration());
            } catch (ParseError error) {
                synchronizeTopLevel();
            }
        }

        return new Program(pkg, imports, types);
    }

    private PackageDeclaration parsePackageDeclaration() {
        String name = parseQualifiedName();
        expect(Tipo.PONTO_VIRGULA, "; esperado após package");
        return new PackageDeclaration(name);
    }

    private ImportDeclaration parseImportDeclaration() {
        boolean isStatic = matchKeyword("static");
        String name = parseQualifiedName();

        if (match(Tipo.PONTO)) {
            if (match(Tipo.OP_VEZES)) {
                name += ".*";
            } else {
                name += "." + consumeIdentifier().lexema;
            }
        }

        expect(Tipo.PONTO_VIRGULA, "; esperado após import");
        return new ImportDeclaration(isStatic, name);
    }

    private TypeDeclaration parseTypeDeclaration() {
        List<String> modifiers = parseModifiers();

        if (matchKeyword("class")) {
            return parseClassOrInterfaceDeclaration(modifiers, true);
        }
        if (matchKeyword("interface")) {
            return parseClassOrInterfaceDeclaration(modifiers, false);
        }

        throw error(current(), "class ou interface esperada");
    }

    private TypeDeclaration parseClassOrInterfaceDeclaration(
            List<String> modifiers, boolean isClass) {
        String name = consumeIdentifier().lexema;
        currentClassName = name;
        updatePosition(previous());

        // Cria novo escopo para a classe
        SymbolTable parentScope = currentScope;
        SymbolTable classScope = new SymbolTable(parentScope, name);
        currentScope = classScope;

        // Registra a classe na tabela de símbolos
        currentScope = parentScope;
        addToSymbolTable(name,
                isClass ? SymbolEntry.SymbolKind.CLASS : SymbolEntry.SymbolKind.INTERFACE,
                name, 0, null, modifiers);
        currentScope = classScope;

        try {
            Optional<List<TypeParameter>> typeParams = Optional.empty();
            if (match(Tipo.OP_MENOR)) {
                typeParams = Optional.of(parseTypeParameters());
            }

            Optional<TypeNode> superType = Optional.empty();
            if (matchKeyword("extends")) {
                superType = Optional.of(parseType());
            }

            List<TypeNode> interfaces = new ArrayList<>();
            if (matchKeyword("implements")) {
                interfaces.add(parseType());
                while (match(Tipo.VIRGULA)) {
                    interfaces.add(parseType());
                }
            }

            if (check(Tipo.ABRE_CHAVETA)) {
                advance();
            } else {
                errorMissingOpenBrace(previous(), isClass ? "classe" : "interface");
            }
            List<ClassMember> members = parseTypeBody(isClass);
            expect(Tipo.FECHA_CHAVETA, "} esperado");

            // Volta ao escopo pai
            currentScope = currentScope.getParent();

            if (isClass) {
                ClassDeclaration decl = new ClassDeclaration(modifiers, name, typeParams, superType, interfaces,
                        members);
                decl.scope = classScope;
                return decl;
            } else {
                InterfaceDeclaration decl = new InterfaceDeclaration(modifiers, name, typeParams, superType,
                        interfaces, members);
                decl.scope = classScope;
                return decl;
            }
        } finally {
            currentScope = parentScope;
        }
    }

    private List<TypeParameter> parseTypeParameters() {
        List<TypeParameter> params = new ArrayList<>();
        params.add(parseTypeParameter());
        while (match(Tipo.VIRGULA)) {
            params.add(parseTypeParameter());
        }
        expect(Tipo.OP_MAIOR, "> esperado");
        return params;
    }

    private TypeParameter parseTypeParameter() {
        String name = consumeIdentifier().lexema;
        List<TypeNode> bounds = new ArrayList<>();
        if (matchKeyword("extends")) {
            bounds.add(parseType());
            while (match(Tipo.OP_E)) {
                bounds.add(parseType());
            }
        }
        return new TypeParameter(name, bounds);
    }

    private List<ClassMember> parseTypeBody(boolean isClass) {
        List<ClassMember> members = new ArrayList<>();
        while (!check(Tipo.FECHA_CHAVETA) && !isAtEnd()) {
            try {
                if (isClass) {
                    members.add(parseClassMember());
                } else {
                    members.add(parseInterfaceMember());
                }
            } catch (ParseError error) {
                members.add(new ErrorMember(error.getMessage()));
                synchronizeClassMember();
            }
        }
        return members;
    }

    private ClassMember parseClassMember() {
        List<String> modifiers = parseModifiers();

        if (check(Tipo.ABRE_CHAVETA)) {
            boolean isStatic = modifiers.contains("static");
            BlockStatement body = parseBlock();
            return new InitializerBlock(isStatic, body);
        }

        boolean isNestedClass = checkKeyword("class");
        boolean isNestedInterface = checkKeyword("interface");
        if (isNestedClass || isNestedInterface) {
            advance();
            return (ClassMember) parseClassOrInterfaceDeclaration(modifiers, isNestedClass);
        }

        // Verifica se é construtor
        if (check(Tipo.IDENTIFICADOR) && checkNext(Tipo.ABRE_PARENTESE)) {
            String name = current().lexema;
            String className = getCurrentClassName();
            if (name.equals(className) && !modifiers.contains("static") && !modifiers.contains("abstract")) {
                return parseConstructorDeclaration(modifiers);
            }
        }

        TypeNode type = parseType();
        String name = consumeIdentifier().lexema;
        Token nameToken = previous(); // ← GUARDA o token do nome para usar no erro

        if (check(Tipo.ABRE_PARENTESE)) {
            return parseMethodDeclaration(modifiers, type, name);
        }

        // É uma declaração de campo
        List<VariableDeclarator> declarators = new ArrayList<>();
        declarators.add(parseVariableDeclaratorWithName(name, nameToken)); // ← PASSA o nameToken

        // Registra o primeiro campo
        updatePosition(previous());
        String initialValue = declarators.get(0).initializer.isPresent()
                ? extractInitialValue(declarators.get(0).initializer.get())
                : null;
        boolean isStatic = modifiers.contains("static");
        addToSymbolTable(name,
                isStatic ? SymbolEntry.SymbolKind.STATIC_FIELD : SymbolEntry.SymbolKind.FIELD,
                type.name, type.arrayDepth + declarators.get(0).arrayDepth, initialValue, modifiers);

        while (match(Tipo.VIRGULA)) {
            VariableDeclarator decl = parseVariableDeclarator();
            declarators.add(decl);

            updatePosition(previous());
            String initVal = decl.initializer.isPresent() ? extractInitialValue(decl.initializer.get()) : null;
            addToSymbolTable(decl.name,
                    isStatic ? SymbolEntry.SymbolKind.STATIC_FIELD : SymbolEntry.SymbolKind.FIELD,
                    type.name, type.arrayDepth + decl.arrayDepth, initVal, modifiers);
        }

        // ← VERIFICA o ';' com o token do nome (em vez de expect direto)
        if (!check(Tipo.PONTO_VIRGULA)) {
            throw errorMissingSemicolon(nameToken, "declaração do campo");
        }
        expect(Tipo.PONTO_VIRGULA, "; esperado após declaração de campo");
        return mark(new FieldDeclaration(modifiers, type, declarators), nameToken);
    }

    private ClassMember parseInterfaceMember() {
        List<String> modifiers = parseModifiers();

        boolean isNestedClass = checkKeyword("class");
        boolean isNestedInterface = checkKeyword("interface");
        if (isNestedClass || isNestedInterface) {
            advance();
            return (ClassMember) parseClassOrInterfaceDeclaration(modifiers, isNestedClass);
        }

        TypeNode type = parseType();
        String name = consumeIdentifier().lexema;
        Token nameToken = previous(); // ← ADICIONAR

        if (match(Tipo.ABRE_PARENTESE)) {
            return parseMethodDeclaration(modifiers, type, name);
        }

        List<VariableDeclarator> declarators = new ArrayList<>();
        declarators.add(parseVariableDeclaratorWithName(name, nameToken));

        // Registra campo da interface
        updatePosition(previous());
        String initialValue = declarators.get(0).initializer.isPresent()
                ? extractInitialValue(declarators.get(0).initializer.get())
                : null;
        addToSymbolTable(name, SymbolEntry.SymbolKind.STATIC_FIELD,
                type.name, type.arrayDepth + declarators.get(0).arrayDepth, initialValue, modifiers);

        while (match(Tipo.VIRGULA)) {
            VariableDeclarator decl = parseVariableDeclarator();
            declarators.add(decl);

            updatePosition(previous());
            String initVal = decl.initializer.isPresent() ? extractInitialValue(decl.initializer.get()) : null;
            addToSymbolTable(decl.name, SymbolEntry.SymbolKind.STATIC_FIELD,
                    type.name, type.arrayDepth + decl.arrayDepth, initVal, modifiers);
        }
        if (!check(Tipo.PONTO_VIRGULA)) {
    throw errorMissingSemicolon(nameToken, "declaração do campo");
}
expect(Tipo.PONTO_VIRGULA, "; esperado");
        return mark(new FieldDeclaration(modifiers, type, declarators), nameToken);
    }

    // MÉTODO ÚNICO parseMethodDeclaration
    private ClassMember parseMethodDeclaration(List<String> modifiers, TypeNode returnType, String name) {
        Token nameTok = previous();
        updatePosition(nameTok);

        // Registra o método na tabela de símbolos
        addToSymbolTable(name, SymbolEntry.SymbolKind.METHOD,
                returnType.name, returnType.arrayDepth, null, modifiers);

        // Cria escopo para o método
        SymbolTable methodScope = new SymbolTable(currentScope, name);
        SymbolTable parentScope = currentScope;
        currentScope = methodScope;

        try {
            expect(Tipo.ABRE_PARENTESE, "( esperado");
            List<Parameter> parameters = parseParameters();
            int arrayDepth = parseArrayDepth();
            Optional<List<TypeNode>> throwsTypes = Optional.empty();

            if (matchKeyword("throws")) {
                throwsTypes = Optional.of(parseTypeList());
            }

            if (match(Tipo.PONTO_VIRGULA)) {
                MethodDeclaration md = mark(new MethodDeclaration(modifiers, returnType, name, parameters,
                        arrayDepth, throwsTypes, null, true), nameTok);
                md.scope = methodScope;
                return md;
            }

            BlockStatement body = parseBlock();
            MethodDeclaration md = mark(new MethodDeclaration(modifiers, returnType, name, parameters, arrayDepth,
                    throwsTypes,
                    body, false), nameTok);
            md.scope = methodScope;

            return md;
        } finally {
            currentScope = parentScope;
        }
    }

    private ClassMember parseConstructorDeclaration(List<String> modifiers) {
        Token nameTok = current();
        String name = consumeIdentifier().lexema;
        updatePosition(nameTok);

        // Registra o construtor na tabela de símbolos
        addToSymbolTable(name, SymbolEntry.SymbolKind.CONSTRUCTOR,
                name, 0, null, modifiers);

        // Cria escopo para o construtor
        SymbolTable ctorScope = new SymbolTable(currentScope, name);
        SymbolTable parentScope = currentScope;
        currentScope = ctorScope;

        try {
            expect(Tipo.ABRE_PARENTESE, "( esperado");
            List<Parameter> parameters = parseParameters();
            Optional<List<TypeNode>> throwsTypes = Optional.empty();

            if (matchKeyword("throws")) {
                throwsTypes = Optional.of(parseTypeList());
            }

            BlockStatement body = parseBlock();

            ConstructorDeclaration ctor = mark(
                    new ConstructorDeclaration(modifiers, name, parameters, throwsTypes, body), nameTok);
            ctor.scope = ctorScope;
            return ctor;
        } finally {
            currentScope = parentScope;
        }
    }

    private List<String> parseModifiers() {
        List<String> modifiers = new ArrayList<>();
        while (isModifierKeyword(current())) {
            modifiers.add(advance().lexema);
        }
        return modifiers;
    }

    private boolean isModifierKeyword(Token token) {
        if (token.tipo != Tipo.PALAVRA_RESERVADA)
            return false;
        switch (token.lexema) {
            case "public":
            case "protected":
            case "private":
            case "abstract":
            case "static":
            case "final":
            case "strictfp":
            case "synchronized":
            case "native":
            case "transient":
            case "volatile":
            case "default":
                return true;
            default:
                return false;
        }
    }

    // MÉTODO ÚNICO parseParameters (com registro na tabela de símbolos)
    private List<Parameter> parseParameters() {
        List<Parameter> parameters = new ArrayList<>();

        if (check(Tipo.FECHA_PARENTESE)) {
            advance();
            return parameters;
        }

        do {
            List<String> modifiers = new ArrayList<>();
            while (matchKeyword("final")) {
                modifiers.add("final");
            }

            TypeNode type = parseType();

            boolean isVarargs = false;
            if (match(Tipo.OP_ELLIPSIS)) {
                isVarargs = true;
            }

            String name = consumeIdentifier().lexema;
            updatePosition(previous());

            int arrayDepthAfterName = 0;
            while (match(Tipo.ABRE_COLCHETE)) {
                expect(Tipo.FECHA_COLCHETE, "] esperado");
                arrayDepthAfterName++;
            }

            int totalArrayDepth = type.arrayDepth + arrayDepthAfterName;

            // Registra o parâmetro na tabela de símbolos
            addToSymbolTable(name, SymbolEntry.SymbolKind.PARAMETER,
                    type.name, totalArrayDepth, null, modifiers);

            parameters.add(new Parameter(modifiers, type, name, totalArrayDepth, isVarargs));
        } while (match(Tipo.VIRGULA));

        expect(Tipo.FECHA_PARENTESE, ") esperado após parâmetros");
        return parameters;
    }

    private List<TypeNode> parseTypeList() {
        List<TypeNode> types = new ArrayList<>();
        types.add(parseType());
        while (match(Tipo.VIRGULA)) {
            types.add(parseType());
        }
        return types;
    }

    private ParseError errorMissingSemicolon(Token previousToken, String context) {
        String message = String.format("Erro em %d:%d - Falta ';' após %s '%s'",
                previousToken.linha, previousToken.col, context, previousToken.lexema);
        ParseError error = new ParseError(message);
        errors.add(error);
        return error;
    }

    private ParseError errorMissingOpenBrace(Token previousToken, String context) {
        String message = String.format("Erro em %d:%d - Falta '{' após declaração da %s '%s'",
                previousToken.linha, previousToken.col, context, previousToken.lexema);
        ParseError error = new ParseError(message);
        errors.add(error);
        return error;
    }

    private ParseError errorMissingExpression(Token operatorToken, String varName) {
        String message = String.format("Erro em %d:%d - Expressão esperada após '%s' para variável '%s'",
                operatorToken.linha, operatorToken.col, operatorToken.lexema, varName);
        ParseError error = new ParseError(message);
        errors.add(error);
        return error;
    }

    private VariableDeclarator parseVariableDeclaratorWithName(String name, Token nameToken) {
        int arrayDepth = parseArrayDepth();
        Optional<Expression> initializer = Optional.empty();
        if (match(Tipo.OP_ATRIBUICAO)) {
            Token operatorToken = previous();

            if (check(Tipo.PONTO_VIRGULA)) {
                throw errorMissingExpression(operatorToken, name);
            }

            if (check(Tipo.ABRE_CHAVETA)) {
                advance();
                initializer = Optional.of(parseArrayInitializer());
            } else {
                initializer = Optional.of(parseExpression());
            }
        }
        return new VariableDeclarator(name, arrayDepth, initializer);
    }

    private VariableDeclarator parseVariableDeclarator() {
        String name = consumeIdentifier().lexema;
        Token nameToken = previous();
        return parseVariableDeclaratorWithName(name, nameToken);
    }

    private TypeNode parseType() {
        TypeNode type;

        if (isPrimitiveType()) {
            String base = advance().lexema;
            type = new TypeNode(base);
        } else if (matchKeyword("void")) {
            type = new TypeNode("void");
        } else {
            type = parseQualifiedType();
        }

        type.arrayDepth += parseArrayDepth();
        return type;
    }

    private boolean isPrimitiveType() {
        if (current().tipo != Tipo.PALAVRA_RESERVADA)
            return false;
        switch (current().lexema) {
            case "boolean":
            case "char":
            case "byte":
            case "short":
            case "int":
            case "long":
            case "float":
            case "double":
                return true;
            default:
                return false;
        }
    }

    private TypeNode parseQualifiedType() {
        String name = consumeIdentifier().lexema;
        TypeNode type = new TypeNode(name);

        if (match(Tipo.OP_MENOR)) {
            List<TypeNode> args = new ArrayList<>();
            args.add(parseTypeArgument());
            while (match(Tipo.VIRGULA)) {
                args.add(parseTypeArgument());
            }
            expect(Tipo.OP_MAIOR, "> esperado em tipo genérico");
            type.typeArguments = args;
        }

        return type;
    }

    private TypeNode parseTypeArgument() {
        if (match(Tipo.OP_TERNARIO)) {
            if (matchKeyword("extends")) {
                TypeNode bound = parseType();
                return new WildcardType(bound, true);
            }
            if (matchKeyword("super")) {
                TypeNode bound = parseType();
                return new WildcardType(bound, false);
            }
            return new WildcardType(null, true);
        }
        return parseType();
    }

    private int parseArrayDepth() {
        int depth = 0;
        while (match(Tipo.ABRE_COLCHETE)) {
            expect(Tipo.FECHA_COLCHETE, "] esperado em declaração de array");
            depth++;
        }
        return depth;
    }

    // ========== INSTRUÇÕES ==========

    private Statement parseStatement() {
        if (check(Tipo.ABRE_CHAVETA))
            return parseBlock();
        if (matchKeyword("if"))
            return parseIfStatement();
        if (matchKeyword("while"))
            return parseWhileStatement();
        if (matchKeyword("do"))
            return parseDoWhileStatement();
        if (matchKeyword("for"))
            return parseForStatement();
        if (matchKeyword("switch"))
            return parseSwitchStatement();
        if (matchKeyword("break"))
            return parseBreakStatement();
        if (matchKeyword("continue"))
            return parseContinueStatement();
        if (matchKeyword("return"))
            return parseReturnStatement();
        if (matchKeyword("throw"))
            return parseThrowStatement();
        if (matchKeyword("try"))
            return parseTryStatement();
        if (matchKeyword("assert"))
            return parseAssertStatement();
        if (matchKeyword("synchronized"))
            return parseSynchronizedStatement();
        if (isStartOfLocalVarDecl())
            return parseLocalVariableDeclaration();
        if (check(Tipo.PONTO_VIRGULA)) {
            advance();
            return new EmptyStatement();
        }
        return parseExpressionStatement();
    }

    private BlockStatement parseBlock() {
        expect(Tipo.ABRE_CHAVETA, "{ esperado");
        List<Statement> statements = new ArrayList<>();
        while (!check(Tipo.FECHA_CHAVETA) && !isAtEnd()) {
            try {
                statements.add(parseStatement());
            } catch (ParseError error) {
                statements.add(new ErrorStatement(error.getMessage()));
                synchronizeStatement();
            }
        }
        expect(Tipo.FECHA_CHAVETA, "} esperado");
        return new BlockStatement(statements);
    }

    private IfStatement parseIfStatement() {
        Token kwTok = previous();
        Expression condition = parseParenthesizedExpression();
        Statement thenBranch = parseStatement();
        Statement elseBranch = null;
        if (matchKeyword("else")) {
            elseBranch = parseStatement();
        }
        return mark(new IfStatement(condition, thenBranch, elseBranch), kwTok);
    }

    private WhileStatement parseWhileStatement() {
        Token kwTok = previous();
        Expression condition = parseParenthesizedExpression();
        Statement body = parseStatement();
        return mark(new WhileStatement(condition, body), kwTok);
    }

    private DoWhileStatement parseDoWhileStatement() {
        Token kwTok = previous();
        Statement body = parseStatement();
        expectKeyword("while", "while esperado após do");
        Expression condition = parseParenthesizedExpression();
        expect(Tipo.PONTO_VIRGULA, "; esperado após do-while");
        return mark(new DoWhileStatement(body, condition), kwTok);
    }

    private Statement parseForStatement() {
        Token kwTok = previous();
        expect(Tipo.ABRE_PARENTESE, "( esperado após for");

        if (isForEachHeader()) {
            return parseForEachHeader(kwTok);
        }

        Optional<Statement> init = Optional.empty();
        if (!check(Tipo.PONTO_VIRGULA)) {
            if (isStartOfLocalVarDecl()) {
                init = Optional.of(parseLocalVariableDeclaration());
            } else {
                Expression expr = parseExpression();
                expect(Tipo.PONTO_VIRGULA, "; esperado");
                init = Optional.of(new ExpressionStatement(expr));
            }
        } else {
            advance();
        }

        Optional<Expression> condition = Optional.empty();
        if (!check(Tipo.PONTO_VIRGULA)) {
            condition = Optional.of(parseExpression());
        }
        expect(Tipo.PONTO_VIRGULA, "; esperado");

        Optional<Expression> update = Optional.empty();
        if (!check(Tipo.FECHA_PARENTESE)) {
            update = Optional.of(parseExpression());
        }
        expect(Tipo.FECHA_PARENTESE, ") esperado");

        Statement body = parseStatement();
        return mark(new ForStatement(init, condition, update, body), kwTok);
    }

    // Lookahead puro (sem consumir/lançar erro) para distinguir
    // for-each ("for (Tipo nome : expr)") do for clássico.
    private boolean isForEachHeader() {
        int saved = pos;
        try {
            if (isPrimitiveType()) {
                pos++;
            } else if (check(Tipo.IDENTIFICADOR)) {
                pos++;
                if (pos < tokens.size() && tokens.get(pos).tipo == Tipo.OP_MENOR) {
                    int depth = 1;
                    pos++;
                    while (pos < tokens.size() && depth > 0) {
                        Tipo t = tokens.get(pos).tipo;
                        if (t == Tipo.OP_MENOR)
                            depth++;
                        else if (t == Tipo.OP_MAIOR)
                            depth--;
                        else if (t == Tipo.PONTO_VIRGULA || t == Tipo.EOF)
                            return false;
                        pos++;
                    }
                }
            } else {
                return false;
            }

            while (pos < tokens.size() && tokens.get(pos).tipo == Tipo.ABRE_COLCHETE) {
                pos++;
                if (pos < tokens.size() && tokens.get(pos).tipo == Tipo.FECHA_COLCHETE) {
                    pos++;
                } else {
                    return false;
                }
            }

            if (pos >= tokens.size() || tokens.get(pos).tipo != Tipo.IDENTIFICADOR) {
                return false;
            }
            pos++;
            return pos < tokens.size() && tokens.get(pos).tipo == Tipo.DOIS_PONTOS;
        } finally {
            pos = saved;
        }
    }

    private ForEachStatement parseForEachHeader(Token kwTok) {
        TypeNode type = parseType();
        Token nameTok = current();
        String name = consumeIdentifier().lexema;
        expect(Tipo.DOIS_PONTOS, ": esperado em for-each");
        Expression iterable = parseExpression();
        expect(Tipo.FECHA_PARENTESE, ") esperado após for-each");

        updatePosition(nameTok);
        addToSymbolTable(name, SymbolEntry.SymbolKind.LOCAL_VARIABLE,
                type.name, type.arrayDepth, null, Collections.emptyList());

        Statement body = parseStatement();
        return mark(new ForEachStatement(type, name, iterable, body), kwTok);
    }

    private SwitchStatement parseSwitchStatement() {
        Token kwTok = previous();
        Expression expression = parseParenthesizedExpression();
        expect(Tipo.ABRE_CHAVETA, "{ esperado após switch");
        List<SwitchGroup> groups = new ArrayList<>();

        while (isSwitchLabelStart() && !isAtEnd()) {
            groups.add(parseSwitchGroup());
        }

        expect(Tipo.FECHA_CHAVETA, "} esperado após switch");
        return mark(new SwitchStatement(expression, groups), kwTok);
    }

    private SwitchGroup parseSwitchGroup() {
        List<SwitchLabel> labels = new ArrayList<>();
        labels.add(parseSwitchLabel());

        while (isSwitchLabelStart()) {
            labels.add(parseSwitchLabel());
        }

        List<Statement> statements = new ArrayList<>();
        while (!isSwitchLabelStart() && !check(Tipo.FECHA_CHAVETA) && !isAtEnd()) {
            try {
                statements.add(parseStatement());
            } catch (ParseError error) {
                statements.add(new ErrorStatement(error.getMessage()));
                synchronizeStatement();
            }
        }

        return new SwitchGroup(labels, statements);
    }

    private boolean isSwitchLabelStart() {
        return checkKeyword("case") || checkKeyword("default");
    }

    private SwitchLabel parseSwitchLabel() {
        if (matchKeyword("case")) {
            Expression expression = parseExpression();
            expect(Tipo.DOIS_PONTOS, ": esperado após case");
            return new CaseLabel(expression);
        }
        if (matchKeyword("default")) {
            expect(Tipo.DOIS_PONTOS, ": esperado após default");
            return new DefaultLabel();
        }
        throw error(current(), "case ou default esperado");
    }

    private BreakStatement parseBreakStatement() {
        Optional<String> label = Optional.empty();
        if (check(Tipo.IDENTIFICADOR)) {
            label = Optional.of(consumeIdentifier().lexema);
        }
        expect(Tipo.PONTO_VIRGULA, "; esperado após break");
        return new BreakStatement(label);
    }

    private ContinueStatement parseContinueStatement() {
        Optional<String> label = Optional.empty();
        if (check(Tipo.IDENTIFICADOR)) {
            label = Optional.of(consumeIdentifier().lexema);
        }
        expect(Tipo.PONTO_VIRGULA, "; esperado após continue");
        return new ContinueStatement(label);
    }

    private ReturnStatement parseReturnStatement() {
        Token kwTok = previous();
        Optional<Expression> value = Optional.empty();
        if (!check(Tipo.PONTO_VIRGULA)) {
            value = Optional.of(parseExpression());
        }
        expect(Tipo.PONTO_VIRGULA, "; esperado após return");
        return mark(new ReturnStatement(value), kwTok);
    }

    private ThrowStatement parseThrowStatement() {
        Expression value = parseExpression();
        expect(Tipo.PONTO_VIRGULA, "; esperado após throw");
        return new ThrowStatement(value);
    }

    private TryStatement parseTryStatement() {
        BlockStatement tryBlock = parseBlock();
        List<CatchClause> catches = new ArrayList<>();
        Optional<BlockStatement> finallyBlock = Optional.empty();

        while (matchKeyword("catch")) {
            catches.add(parseCatchClause());
        }

        if (matchKeyword("finally")) {
            finallyBlock = Optional.of(parseBlock());
        }

        if (catches.isEmpty() && !finallyBlock.isPresent()) {
            throw error(previous(), "catch ou finally esperado após try");
        }

        return new TryStatement(tryBlock, catches, finallyBlock);
    }

    private CatchClause parseCatchClause() {
        expect(Tipo.ABRE_PARENTESE, "( esperado após catch");
        TypeNode type = parseType();
        String name = consumeIdentifier().lexema;

        // Registra o parâmetro do catch
        updatePosition(previous());
        addToSymbolTable(name, SymbolEntry.SymbolKind.PARAMETER,
                type.name, type.arrayDepth, null, Collections.emptyList());

        expect(Tipo.FECHA_PARENTESE, ") esperado após parâmetro do catch");
        BlockStatement body = parseBlock();
        return new CatchClause(type, name, body);
    }

    private AssertStatement parseAssertStatement() {
        Expression condition = parseExpression();
        Optional<Expression> message = Optional.empty();
        if (match(Tipo.DOIS_PONTOS)) {
            message = Optional.of(parseExpression());
        }
        expect(Tipo.PONTO_VIRGULA, "; esperado após assert");
        return new AssertStatement(condition, message);
    }

    private SynchronizedStatement parseSynchronizedStatement() {
        Expression expression = parseParenthesizedExpression();
        BlockStatement body = parseBlock();
        return new SynchronizedStatement(expression, body);
    }

    // MÉTODO ÚNICO parseLocalVariableDeclaration (com registro na tabela de
    // símbolos)
    private LocalVariableDeclaration parseLocalVariableDeclaration() {
        Token startTok = current();
        TypeNode type = parseType();
        List<VariableDeclarator> declarators = new ArrayList<>();

        VariableDeclarator first = parseVariableDeclarator();
        declarators.add(first);

        // Registra a primeira variável
        updatePosition(previous());
        String initialValue = first.initializer.isPresent() ? extractInitialValue(first.initializer.get()) : null;
        addToSymbolTable(first.name, SymbolEntry.SymbolKind.LOCAL_VARIABLE,
                type.name, type.arrayDepth + first.arrayDepth, initialValue,
                Collections.emptyList());

        while (match(Tipo.VIRGULA)) {
            VariableDeclarator decl = parseVariableDeclarator();
            declarators.add(decl);

            updatePosition(previous());
            String initVal = decl.initializer.isPresent() ? extractInitialValue(decl.initializer.get()) : null;
            addToSymbolTable(decl.name, SymbolEntry.SymbolKind.LOCAL_VARIABLE,
                    type.name, type.arrayDepth + decl.arrayDepth, initVal,
                    Collections.emptyList());
        }

        expect(Tipo.PONTO_VIRGULA, "; esperado após declaração de variável");
        return mark(new LocalVariableDeclaration(type, declarators), startTok);
    }

    private ExpressionStatement parseExpressionStatement() {
        Expression expression = parseExpression();
        expect(Tipo.PONTO_VIRGULA, "; esperado após expressão");
        return new ExpressionStatement(expression);
    }

    // ========== EXPRESSÕES ==========
    // (Mantém todo o código de expressões como estava antes - parseExpression até
    // parsePrimary)

    private Expression parseExpression() {
        return parseAssignment();
    }

    private Expression parseAssignment() {
        Expression left = parseConditional();
        if (isAssignmentOperator()) {
            Token opTok = previous();
            Expression right = parseAssignment();
            return mark(new AssignmentExpression(left, opTok.lexema, right), opTok);
        }
        return left;
    }

    private boolean isAssignmentOperator() {
        return match(Tipo.OP_ATRIBUICAO, Tipo.OP_MAIS_IGUAL, Tipo.OP_MENOS_IGUAL,
                Tipo.OP_VEZES_IGUAL, Tipo.OP_DIVIDIR_IGUAL, Tipo.OP_MODULO_IGUAL,
                Tipo.OP_E_IGUAL, Tipo.OP_OU_IGUAL, Tipo.OP_XOR_IGUAL,
                Tipo.OP_SHIFT_LEFT_IGUAL, Tipo.OP_SHIFT_RIGHT_IGUAL, Tipo.OP_SHIFT_UNSIGNED_IGUAL);
    }

    private Expression parseConditional() {
        Expression expr = parseOr();
        if (match(Tipo.OP_TERNARIO)) {
            Token qTok = previous();
            Expression thenExpr = parseExpression();
            expect(Tipo.DOIS_PONTOS, ": esperado em operador ternário");
            Expression elseExpr = parseConditional();
            return mark(new ConditionalExpression(expr, thenExpr, elseExpr), qTok);
        }
        return expr;
    }

    private Expression parseOr() {
        Expression expr = parseAnd();
        while (match(Tipo.OP_OU_LOGICO)) {
            Token opTok = previous();
            Expression right = parseAnd();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseAnd() {
        Expression expr = parseBitOr();
        while (match(Tipo.OP_E_LOGICO)) {
            Token opTok = previous();
            Expression right = parseBitOr();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseBitOr() {
        Expression expr = parseXor();
        while (match(Tipo.OP_OU)) {
            Token opTok = previous();
            Expression right = parseXor();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseXor() {
        Expression expr = parseBitAnd();
        while (match(Tipo.OP_XOR)) {
            Token opTok = previous();
            Expression right = parseBitAnd();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseBitAnd() {
        Expression expr = parseEquality();
        while (match(Tipo.OP_E)) {
            Token opTok = previous();
            Expression right = parseEquality();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseEquality() {
        Expression expr = parseRelational();
        while (match(Tipo.OP_IGUAL, Tipo.OP_DIFERENTE)) {
            Token opTok = previous();
            Expression right = parseRelational();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseRelational() {
        Expression expr = parseShift();
        while (true) {
            if (match(Tipo.OP_MENOR, Tipo.OP_MAIOR, Tipo.OP_MENOR_IGUAL, Tipo.OP_MAIOR_IGUAL)) {
                Token opTok = previous();
                Expression right = parseShift();
                expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
            } else if (matchKeyword("instanceof")) {
                Token kwTok = previous();
                TypeNode type = parseType();
                expr = mark(new InstanceOfExpression(expr, type), kwTok);
            } else {
                break;
            }
        }
        return expr;
    }

    private Expression parseShift() {
        Expression expr = parseAdditive();
        while (match(Tipo.OP_SHIFT_LEFT, Tipo.OP_SHIFT_RIGHT, Tipo.OP_SHIFT_UNSIGNED)) {
            Token opTok = previous();
            Expression right = parseAdditive();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseAdditive() {
        Expression expr = parseMultiplicative();
        while (match(Tipo.OP_MAIS, Tipo.OP_MENOS)) {
            Token opTok = previous();
            Expression right = parseMultiplicative();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseMultiplicative() {
        Expression expr = parseUnary();
        while (match(Tipo.OP_VEZES, Tipo.OP_DIVIDIR, Tipo.OP_MODULO)) {
            Token opTok = previous();
            Expression right = parseUnary();
            expr = mark(new BinaryExpression(expr, opTok.lexema, right), opTok);
        }
        return expr;
    }

    private Expression parseUnary() {
        if (match(Tipo.OP_INCREMENTO, Tipo.OP_DECREMENTO, Tipo.OP_MAIS, Tipo.OP_MENOS,
                Tipo.OP_NAO, Tipo.OP_COMPLEMENTO)) {
            Token opTok = previous();
            Expression operand = parseUnary();
            return mark(new UnaryExpression(opTok.lexema, operand), opTok);
        }

        if (check(Tipo.ABRE_PARENTESE)) {
            int saved = pos;
            pos++;

            boolean isCast = false;

            if (isPrimitiveType()) {
                advance();
                if (check(Tipo.FECHA_PARENTESE)) {
                    int saved2 = pos;
                    pos++;
                    if (isCastFollower()) {
                        isCast = true;
                    }
                    pos = saved2;
                }
            } else if (check(Tipo.IDENTIFICADOR)) {
                advance();
                if (check(Tipo.OP_MENOR)) {
                    int depth = 1;
                    pos++;
                    while (pos < tokens.size() && depth > 0) {
                        Tipo t = tokens.get(pos).tipo;
                        if (t == Tipo.OP_MENOR)
                            depth++;
                        else if (t == Tipo.OP_MAIOR)
                            depth--;
                        pos++;
                    }
                }
                while (check(Tipo.ABRE_COLCHETE)) {
                    pos++;
                    if (check(Tipo.FECHA_COLCHETE))
                        pos++;
                }
                if (check(Tipo.FECHA_PARENTESE)) {
                    int saved2 = pos;
                    pos++;
                    if (isCastFollower()) {
                        isCast = true;
                    }
                    pos = saved2;
                }
            }

            Token parenTok = tokens.get(saved);

            if (isCast) {
                pos = saved;
                pos++;
                TypeNode type = parseType();
                expect(Tipo.FECHA_PARENTESE, ") esperado em cast");
                Expression operand = parseUnary();
                return mark(new CastExpression(type, operand), parenTok);
            }

            pos = saved;
            pos++;
            Expression expr = parseExpression();
            expect(Tipo.FECHA_PARENTESE, ") esperado");
            return mark(new ParenthesizedExpression(expr), parenTok);
        }

        return parsePostfix();
    }

    private boolean isCastFollower() {
        if (isAtEnd())
            return false;
        Tipo t = current().tipo;
        String lex = current().lexema;
        return t == Tipo.LITERAL_INTEIRO || t == Tipo.LITERAL_LONG
                || t == Tipo.LITERAL_FLOAT || t == Tipo.LITERAL_DOUBLE
                || t == Tipo.LITERAL_TEXTO || t == Tipo.LITERAL_CARACTERE
                || t == Tipo.LITERAL_ESPECIAL
                || t == Tipo.IDENTIFICADOR
                || t == Tipo.ABRE_PARENTESE
                || t == Tipo.OP_NAO || t == Tipo.OP_COMPLEMENTO
                || t == Tipo.OP_INCREMENTO || t == Tipo.OP_DECREMENTO
                || (t == Tipo.PALAVRA_RESERVADA && (lex.equals("this") || lex.equals("super") || lex.equals("new")));
    }

    private Expression parsePostfix() {
        Expression expr = parsePrimary();

        while (true) {
            if (match(Tipo.ABRE_COLCHETE)) {
                Token brTok = previous();
                Expression index = parseExpression();
                expect(Tipo.FECHA_COLCHETE, "] esperado");
                expr = mark(new ArrayAccessExpression(expr, index), brTok);
            } else if (match(Tipo.ABRE_PARENTESE)) {
                int callLine = expr.line;
                int callCol = expr.col;
                List<Expression> args = parseArguments();
                String name = "";
                if (expr instanceof IdentifierExpression idExpr) {
                    name = idExpr.name;
                }
                expr = markPos(new MethodInvocationExpression(null, name, args), callLine, callCol);
            } else if (match(Tipo.PONTO)) {
                if (matchKeyword("class")) {
                    Token clsTok = previous();
                    expr = mark(new ClassLiteralExpression(expr), clsTok);
                } else if (matchKeyword("new")) {
                    Token newTok = previous();
                    TypeNode type = parseType();
                    expect(Tipo.ABRE_PARENTESE, "( esperado em new interno");
                    List<Expression> args = parseArguments();
                    Optional<BlockStatement> body = Optional.empty();
                    if (check(Tipo.ABRE_CHAVETA)) {
                        body = Optional.of(parseBlock());
                    }
                    expr = mark(new InnerNewExpression(expr, type, args, body), newTok);
                } else {
                    Token nameTok = current();
                    String name = consumeIdentifier().lexema;
                    if (match(Tipo.ABRE_PARENTESE)) {
                        List<Expression> args = parseArguments();
                        expr = mark(new MethodInvocationExpression(expr, name, args), nameTok);
                    } else {
                        expr = mark(new FieldAccessExpression(expr, name), nameTok);
                    }
                }
            } else if (match(Tipo.OP_INCREMENTO, Tipo.OP_DECREMENTO)) {
                Token opTok = previous();
                expr = mark(new PostfixExpression(expr, opTok.lexema), opTok);
            } else {
                break;
            }
        }

        return expr;
    }

    private Expression parsePrimary() {
        if (match(Tipo.LITERAL_INTEIRO, Tipo.LITERAL_LONG, Tipo.LITERAL_FLOAT,
                Tipo.LITERAL_DOUBLE, Tipo.LITERAL_TEXTO, Tipo.LITERAL_CARACTERE)) {
            Token litTok = previous();
            return mark(new LiteralExpression(litTok.lexema, litTok.tipo), litTok);
        }

        if (check(Tipo.LITERAL_ESPECIAL)) {
            Token litTok = advance();
            return mark(new LiteralExpression(litTok.lexema, Tipo.LITERAL_ESPECIAL), litTok);
        }

        if (matchKeyword("this")) {
            Token kwTok = previous();
            if (match(Tipo.ABRE_PARENTESE)) {
                List<Expression> args = parseArguments();
                return mark(new ThisConstructorCallExpression(args), kwTok);
            }
            return mark(new ThisExpression(), kwTok);
        }

        if (matchKeyword("super")) {
            Token kwTok = previous();
            if (match(Tipo.ABRE_PARENTESE)) {
                List<Expression> args = parseArguments();
                return mark(new SuperConstructorCallExpression(args), kwTok);
            }
            return mark(new SuperExpression(), kwTok);
        }

        if (matchKeyword("new"))
            return parseNewExpression();

        if (check(Tipo.IDENTIFICADOR)) {
            Token idTok = current();
            String name = consumeIdentifier().lexema;
            Optional<List<TypeNode>> typeArgs = Optional.empty();

            if (check(Tipo.OP_MENOR) && isNextTokenAType()) {
                advance();
                typeArgs = Optional.of(parseTypeArguments());
            }

            return mark(new IdentifierExpression(name, typeArgs), idTok);
        }

        throw error(current(), "Expressão primária esperada");
    }

    private boolean isNextTokenAType() {
        int saved = pos;
        pos++;

        boolean result = false;

        if (isPrimitiveType()) {
            result = true;
        } else if (check(Tipo.IDENTIFICADOR)) {
            pos++;
            if (check(Tipo.OP_MENOR)) {
                int depth = 1;
                pos++;
                while (pos < tokens.size() && depth > 0) {
                    Tipo t = tokens.get(pos).tipo;
                    if (t == Tipo.OP_MENOR)
                        depth++;
                    else if (t == Tipo.OP_MAIOR)
                        depth--;
                    else if (t == Tipo.EOF || t == Tipo.PONTO_VIRGULA)
                        break;
                    pos++;
                }
            }
            while (check(Tipo.ABRE_COLCHETE)) {
                pos++;
                if (check(Tipo.FECHA_COLCHETE))
                    pos++;
            }
            if (check(Tipo.OP_MAIOR)) {
                result = true;
            }
        } else if (check(Tipo.OP_TERNARIO)) {
            result = true;
        }

        pos = saved;
        return result;
    }

    private List<TypeNode> parseTypeArguments() {
        List<TypeNode> args = new ArrayList<>();
        args.add(parseTypeArgument());
        while (match(Tipo.VIRGULA)) {
            args.add(parseTypeArgument());
        }
        expect(Tipo.OP_MAIOR, "> esperado");
        return args;
    }

    private Expression parseNewExpression() {
        Token newTok = previous();
        TypeNode type = parseType();

        if (match(Tipo.ABRE_PARENTESE)) {
            List<Expression> args = parseArguments();
            Optional<BlockStatement> body = Optional.empty();
            if (check(Tipo.ABRE_CHAVETA)) {
                body = Optional.of(parseBlock());
            }
            return mark(new NewExpression(type, args, body), newTok);
        }

        if (match(Tipo.ABRE_COLCHETE)) {
            List<Expression> dimensions = new ArrayList<>();

            if (check(Tipo.FECHA_COLCHETE)) {
                advance();
                dimensions.add(null);
            } else {
                dimensions.add(parseExpression());
                expect(Tipo.FECHA_COLCHETE, "] esperado");
            }

            while (match(Tipo.ABRE_COLCHETE)) {
                if (check(Tipo.FECHA_COLCHETE)) {
                    advance();
                    dimensions.add(null);
                } else {
                    dimensions.add(parseExpression());
                    expect(Tipo.FECHA_COLCHETE, "] esperado");
                }
            }

            Optional<ArrayInitializer> initializer = Optional.empty();
            if (check(Tipo.ABRE_CHAVETA)) {
                advance();
                initializer = Optional.of(parseArrayInitializer());
            }

            return mark(new NewArrayExpression(type, dimensions, initializer), newTok);
        }

        throw error(current(), "( ou [ esperado após new");
    }

    private ArrayInitializer parseArrayInitializer() {
        List<Expression> values = new ArrayList<>();

        if (!check(Tipo.FECHA_CHAVETA)) {
            do {
                if (check(Tipo.ABRE_CHAVETA)) {
                    advance();
                    values.add(parseArrayInitializer());
                } else {
                    values.add(parseExpression());
                }
            } while (match(Tipo.VIRGULA) && !check(Tipo.FECHA_CHAVETA));
        }

        expect(Tipo.FECHA_CHAVETA, "} esperado em inicializador de array");
        return new ArrayInitializer(values);
    }

    private List<Expression> parseArguments() {
        List<Expression> arguments = new ArrayList<>();
        if (check(Tipo.FECHA_PARENTESE)) {
            advance();
            return arguments;
        }
        do {
            arguments.add(parseExpression());
        } while (match(Tipo.VIRGULA));
        expect(Tipo.FECHA_PARENTESE, ") esperado após argumentos");
        return arguments;
    }

    private Expression parseParenthesizedExpression() {
        expect(Tipo.ABRE_PARENTESE, "( esperado");
        Expression expr = parseExpression();
        expect(Tipo.FECHA_PARENTESE, ") esperado");
        return expr;
    }

    // ========== UTILITÁRIOS ==========

    private String parseQualifiedName() {
        String name = consumeIdentifier().lexema;
        while (match(Tipo.PONTO)) {
            name += "." + consumeIdentifier().lexema;
        }
        return name;
    }

    private boolean isStartOfLocalVarDecl() {
        if (isPrimitiveType())
            return true;
        if (!check(Tipo.IDENTIFICADOR))
            return false;

        int saved = pos;
        try {
            pos++;
            if (pos < tokens.size() && tokens.get(pos).tipo == Tipo.OP_MENOR) {
                int depth = 1;
                int i = pos + 1;
                while (i < tokens.size() && depth > 0) {
                    Tipo t = tokens.get(i).tipo;
                    if (t == Tipo.OP_MENOR)
                        depth++;
                    else if (t == Tipo.OP_MAIOR)
                        depth--;
                    else if (t == Tipo.PONTO_VIRGULA || t == Tipo.ABRE_CHAVETA || t == Tipo.EOF)
                        break;
                    i++;
                }
                if (depth == 0 && i < tokens.size() && tokens.get(i).tipo == Tipo.IDENTIFICADOR) {
                    return true;
                }
                return false;
            }

            while (pos < tokens.size() && tokens.get(pos).tipo == Tipo.ABRE_COLCHETE) {
                pos++;
                if (pos < tokens.size() && tokens.get(pos).tipo == Tipo.FECHA_COLCHETE) {
                    pos++;
                } else {
                    return false;
                }
            }

            return pos < tokens.size() && tokens.get(pos).tipo == Tipo.IDENTIFICADOR;
        } finally {
            pos = saved;
        }
    }

    private String getCurrentClassName() {
        for (int i = pos - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.lexema.equals("class") && i + 1 < tokens.size()) {
                return tokens.get(i + 1).lexema;
            }
        }
        return "";
    }

    private Token consumeIdentifier() {
        if (check(Tipo.IDENTIFICADOR)) {
            return advance();
        }
        throw error(current(), "Identificador esperado");
    }

    private void expect(Tipo tipo, String message) {
        if (check(tipo)) {
            advance();
            return;
        }
        throw error(current(), message);
    }

    private void expectKeyword(String keyword, String message) {
        if (matchKeyword(keyword))
            return;
        throw error(current(), message);
    }

    private boolean match(Tipo... types) {
        for (Tipo type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean matchKeyword(String... keywords) {
        for (String keyword : keywords) {
            if (current().tipo == Tipo.PALAVRA_RESERVADA && current().lexema.equals(keyword)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean checkKeyword(String keyword) {
        return current().tipo == Tipo.PALAVRA_RESERVADA && current().lexema.equals(keyword);
    }

    private boolean check(Tipo type) {
        if (isAtEnd())
            return false;
        return current().tipo == type;
    }

    private boolean checkNext(Tipo type) {
        if (pos + 1 >= tokens.size())
            return false;
        return tokens.get(pos + 1).tipo == type;
    }

    private Token advance() {
        if (!isAtEnd())
            pos++;
        return previous();
    }

    private boolean isAtEnd() {
        return current().tipo == Tipo.EOF;
    }

    private Token current() {
        return tokens.get(pos);
    }

    private Token previous() {
        return tokens.get(pos - 1);
    }

    private void synchronizeTopLevel() {
        synchronizeUntil(Tipo.PONTO_VIRGULA, Tipo.FECHA_CHAVETA);
        while (!isAtEnd() && !isTopLevelStart()) {
            advance();
        }
    }

    private void synchronizeClassMember() {
        if (match(Tipo.PONTO_VIRGULA)) {
            return;
        }
        while (!isAtEnd()) {
            if (check(Tipo.FECHA_CHAVETA) || isClassMemberStart()) {
                return;
            }
            if (previousIsSemicolon()) {
                return;
            }
            advance();
        }
    }

    private void synchronizeStatement() {
        if (match(Tipo.PONTO_VIRGULA)) {
            return;
        }
        while (!isAtEnd()) {
            if (check(Tipo.FECHA_CHAVETA) || isStatementStart()) {
                return;
            }
            if (previousIsSemicolon()) {
                return;
            }
            advance();
        }
    }

    private void synchronizeUntil(Tipo... stopTokens) {
        while (!isAtEnd()) {
            for (Tipo stopToken : stopTokens) {
                if (check(stopToken)) {
                    if (stopToken == Tipo.PONTO_VIRGULA) {
                        advance();
                    }
                    return;
                }
            }
            if (isTopLevelStart()) {
                return;
            }
            advance();
        }
    }

    private boolean previousIsSemicolon() {
        return pos > 0 && previous().tipo == Tipo.PONTO_VIRGULA;
    }

    private boolean isTopLevelStart() {
        if (checkKeyword("class") || checkKeyword("interface")) {
            return true;
        }
        return isModifierKeyword(current());
    }

    private boolean isClassMemberStart() {
        if (isTopLevelStart() || check(Tipo.ABRE_CHAVETA) || isPrimitiveType() || check(Tipo.IDENTIFICADOR)) {
            return true;
        }
        return checkKeyword("void");
    }

    private boolean isStatementStart() {
        if (check(Tipo.ABRE_CHAVETA) || check(Tipo.PONTO_VIRGULA) || check(Tipo.IDENTIFICADOR) || isPrimitiveType()) {
            return true;
        }
        if (current().tipo != Tipo.PALAVRA_RESERVADA) {
            return false;
        }
        switch (current().lexema) {
            case "if":
            case "while":
            case "do":
            case "for":
            case "switch":
            case "break":
            case "continue":
            case "return":
            case "throw":
            case "try":
            case "assert":
            case "synchronized":
            case "new":
            case "this":
            case "super":
                return true;
            default:
                return false;
        }
    }

    private ParseError error(Token token, String message) {
        ParseError error = new ParseError(String.format("Erro em %d:%d - %s (token='%s')",
                token.linha, token.col, message, token.lexema));
        errors.add(error);
        return error;
    }

    private static class ParseError extends RuntimeException {
        public ParseError(String message) {
            super(message);
        }
    }

    // ========== AST NODES (APENAS OS ESSENCIAIS) ==========

    public static class Node {
        public int line = 0;
        public int col = 0;

        public String toString(String indent) {
            return toString();
        }

        public String toString() {
            return "";
        }
    }

    public static class Program extends Node {
        public final Optional<PackageDeclaration> packageDecl;
        public final List<ImportDeclaration> imports;
        public final List<TypeDeclaration> types;

        public Program(Optional<PackageDeclaration> packageDecl, List<ImportDeclaration> imports,
                List<TypeDeclaration> types) {
            this.packageDecl = packageDecl;
            this.imports = imports;
            this.types = types;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Program\n");
            if (packageDecl.isPresent())
                sb.append("  ").append(packageDecl.get()).append("\n");
            for (ImportDeclaration i : imports)
                sb.append("  ").append(i).append("\n");
            for (TypeDeclaration t : types)
                sb.append("  ").append(t).append("\n");
            return sb.toString();
        }
    }

    public static class PackageDeclaration extends Node {
        public final String name;

        public PackageDeclaration(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "Package: " + name;
        }
    }

    public static class ImportDeclaration extends Node {
        public final boolean isStatic;
        public final String name;

        public ImportDeclaration(boolean isStatic, String name) {
            this.isStatic = isStatic;
            this.name = name;
        }

        @Override
        public String toString() {
            return "Import: " + (isStatic ? "static " : "") + name;
        }
    }

    public static abstract class TypeDeclaration extends Node {
        public final List<String> modifiers;
        public final String name;
        public final Optional<List<TypeParameter>> typeParameters;

        public TypeDeclaration(List<String> modifiers, String name, Optional<List<TypeParameter>> typeParameters) {
            this.modifiers = modifiers;
            this.name = name;
            this.typeParameters = typeParameters;
        }
    }

    public static class TypeParameter extends Node {
        public final String name;
        public final List<TypeNode> bounds;

        public TypeParameter(String name, List<TypeNode> bounds) {
            this.name = name;
            this.bounds = bounds;
        }

        @Override
        public String toString() {
            return name + (bounds.isEmpty() ? "" : " extends " + bounds);
        }
    }

    public static class ClassDeclaration extends TypeDeclaration {
        public final Optional<TypeNode> superClass;
        public final List<TypeNode> interfaces;
        public final List<ClassMember> members;
        public SymbolTable scope;

        public ClassDeclaration(List<String> modifiers, String name, Optional<List<TypeParameter>> typeParams,
                Optional<TypeNode> superClass, List<TypeNode> interfaces, List<ClassMember> members) {
            super(modifiers, name, typeParams);
            this.superClass = superClass;
            this.interfaces = interfaces;
            this.members = members;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Class: " + name);
            if (!modifiers.isEmpty())
                sb.append(" [").append(String.join(" ", modifiers)).append("]");
            if (typeParameters.isPresent())
                sb.append("<").append(typeParameters.get()).append(">");
            sb.append("\n");
            if (superClass.isPresent())
                sb.append("  extends ").append(superClass.get()).append("\n");
            if (!interfaces.isEmpty())
                sb.append("  implements ").append(interfaces).append("\n");
            for (ClassMember m : members)
                sb.append("  ").append(m).append("\n");
            return sb.toString();
        }
    }

    public static class InterfaceDeclaration extends TypeDeclaration {
        public final Optional<TypeNode> superInterface;
        public final List<TypeNode> interfaces;
        public final List<ClassMember> members;
        public SymbolTable scope;

        public InterfaceDeclaration(List<String> modifiers, String name, Optional<List<TypeParameter>> typeParams,
                Optional<TypeNode> superInterface, List<TypeNode> interfaces, List<ClassMember> members) {
            super(modifiers, name, typeParams);
            this.superInterface = superInterface;
            this.interfaces = interfaces;
            this.members = members;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Interface: " + name);
            if (!modifiers.isEmpty())
                sb.append(" [").append(String.join(" ", modifiers)).append("]");
            if (typeParameters.isPresent())
                sb.append("<").append(typeParameters.get()).append(">");
            sb.append("\n");
            if (superInterface.isPresent())
                sb.append("  extends ").append(superInterface.get()).append("\n");
            if (!interfaces.isEmpty())
                sb.append("  implements ").append(interfaces).append("\n");
            for (ClassMember m : members)
                sb.append("  ").append(m).append("\n");
            return sb.toString();
        }
    }

    public interface ClassMember {
        String toString();
    }

    public static class ErrorMember extends Node implements ClassMember {
        public final String message;

        public ErrorMember(String message) {
            this.message = message;
        }

        @Override
        public String toString() {
            return "Erro de membro: " + message;
        }
    }

    public static class FieldDeclaration extends Node implements ClassMember {
        public final List<String> modifiers;
        public final TypeNode type;
        public final List<VariableDeclarator> declarators;

        public FieldDeclaration(List<String> modifiers, TypeNode type, List<VariableDeclarator> declarators) {
            this.modifiers = modifiers;
            this.type = type;
            this.declarators = declarators;
        }

        @Override
        public String toString() {
            return "Field: " + type + " " + declarators;
        }
    }

    public static class MethodDeclaration extends Node implements ClassMember {
        public final List<String> modifiers;
        public final TypeNode returnType;
        public final String name;
        public final List<Parameter> parameters;
        public final int arrayDepth;
        public final Optional<List<TypeNode>> throwsTypes;
        public final BlockStatement body;
        public final boolean isAbstract;
        public SymbolTable scope;

        public MethodDeclaration(List<String> modifiers, TypeNode returnType, String name, List<Parameter> parameters,
                int arrayDepth, Optional<List<TypeNode>> throwsTypes, BlockStatement body, boolean isAbstract) {
            this.modifiers = modifiers;
            this.returnType = returnType;
            this.name = name;
            this.parameters = parameters;
            this.arrayDepth = arrayDepth;
            this.throwsTypes = throwsTypes;
            this.body = body;
            this.isAbstract = isAbstract;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Method: " + returnType);
            for (int i = 0; i < arrayDepth; i++)
                sb.append("[]");
            sb.append(" ").append(name).append("(").append(parameters).append(")");
            if (!modifiers.isEmpty())
                sb.append(" [").append(String.join(" ", modifiers)).append("]");
            if (throwsTypes.isPresent())
                sb.append(" throws ").append(throwsTypes.get());
            if (body != null)
                sb.append("\n    ").append(body.toString().replace("\n", "\n    "));
            return sb.toString();
        }
    }

    public static class ConstructorDeclaration extends Node implements ClassMember {
        public final List<String> modifiers;
        public final String name;
        public final List<Parameter> parameters;
        public final Optional<List<TypeNode>> throwsTypes;
        public final BlockStatement body;
        public SymbolTable scope;

        public ConstructorDeclaration(List<String> modifiers, String name, List<Parameter> parameters,
                Optional<List<TypeNode>> throwsTypes, BlockStatement body) {
            this.modifiers = modifiers;
            this.name = name;
            this.parameters = parameters;
            this.throwsTypes = throwsTypes;
            this.body = body;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Constructor: " + name + "(" + parameters + ")");
            if (!modifiers.isEmpty())
                sb.append(" [").append(String.join(" ", modifiers)).append("]");
            if (throwsTypes.isPresent())
                sb.append(" throws ").append(throwsTypes.get());
            if (body != null)
                sb.append("\n    ").append(body.toString().replace("\n", "\n    "));
            return sb.toString();
        }
    }

    public static class InitializerBlock extends Node implements ClassMember {
        public final boolean isStatic;
        public final BlockStatement body;

        public InitializerBlock(boolean isStatic, BlockStatement body) {
            this.isStatic = isStatic;
            this.body = body;
        }

        @Override
        public String toString() {
            return (isStatic ? "Static" : "Instance") + " initializer:\n    " + body.toString().replace("\n", "\n    ");
        }
    }

    public static class Parameter extends Node {
        public final List<String> modifiers;
        public final TypeNode type;
        public final String name;
        public final int arrayDepth;
        public final boolean isVarargs;

        public Parameter(List<String> modifiers, TypeNode type, String name, int arrayDepth, boolean isVarargs) {
            this.modifiers = modifiers;
            this.type = type;
            this.name = name;
            this.arrayDepth = arrayDepth;
            this.isVarargs = isVarargs;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (!modifiers.isEmpty())
                sb.append(String.join(" ", modifiers)).append(" ");
            sb.append(type);
            if (isVarargs)
                sb.append("...");
            for (int i = 0; i < arrayDepth; i++)
                sb.append("[]");
            return sb.append(" ").append(name).toString();
        }
    }

    public static class VariableDeclarator extends Node {
        public final String name;
        public final int arrayDepth;
        public final Optional<Expression> initializer;

        public VariableDeclarator(String name, int arrayDepth, Optional<Expression> initializer) {
            this.name = name;
            this.arrayDepth = arrayDepth;
            this.initializer = initializer;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name);
            for (int i = 0; i < arrayDepth; i++)
                sb.append("[]");
            if (initializer.isPresent())
                sb.append(" = ").append(initializer.get());
            return sb.toString();
        }
    }

    public static class TypeNode extends Node {
        public String name;
        public List<TypeNode> typeArguments = new ArrayList<>();
        public int arrayDepth = 0;

        public TypeNode(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name);
            if (!typeArguments.isEmpty())
                sb.append("<").append(typeArguments).append(">");
            for (int i = 0; i < arrayDepth; i++)
                sb.append("[]");
            return sb.toString();
        }
    }

    public static class WildcardType extends TypeNode {
        public final TypeNode bound;
        public final boolean isExtends;

        public WildcardType(TypeNode bound, boolean isExtends) {
            super("?");
            this.bound = bound;
            this.isExtends = isExtends;
        }

        @Override
        public String toString() {
            if (bound == null)
                return "?";
            return "? " + (isExtends ? "extends " : "super ") + bound;
        }
    }

    public static abstract class Statement extends Node {
    }

    public static abstract class Expression extends Node {
    }

    public static class ErrorStatement extends Statement {
        public final String message;

        public ErrorStatement(String message) {
            this.message = message;
        }

        @Override
        public String toString() {
            return "Erro de statement: " + message;
        }
    }

    public static class BlockStatement extends Statement {
        public final List<Statement> statements;

        public BlockStatement(List<Statement> statements) {
            this.statements = statements;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Block:\n");
            for (Statement s : statements)
                sb.append("    ").append(s.toString().replace("\n", "\n    ")).append("\n");
            return sb.toString();
        }
    }

    public static class IfStatement extends Statement {
        public final Expression condition;
        public final Statement thenBranch;
        public final Statement elseBranch;

        public IfStatement(Expression condition, Statement thenBranch, Statement elseBranch) {
            this.condition = condition;
            this.thenBranch = thenBranch;
            this.elseBranch = elseBranch;
        }

        @Override
        public String toString() {
            String result = "If: " + condition + "\n    " + thenBranch.toString().replace("\n", "\n    ");
            if (elseBranch != null)
                result += "\n    Else:\n    " + elseBranch.toString().replace("\n", "\n    ");
            return result;
        }
    }

    public static class WhileStatement extends Statement {
        public final Expression condition;
        public final Statement body;

        public WhileStatement(Expression condition, Statement body) {
            this.condition = condition;
            this.body = body;
        }

        @Override
        public String toString() {
            return "While: " + condition + "\n    " + body.toString().replace("\n", "\n    ");
        }
    }

    public static class DoWhileStatement extends Statement {
        public final Statement body;
        public final Expression condition;

        public DoWhileStatement(Statement body, Expression condition) {
            this.body = body;
            this.condition = condition;
        }

        @Override
        public String toString() {
            return "DoWhile:\n    " + body.toString().replace("\n", "\n    ") + "\n    Condition: " + condition;
        }
    }

    public static class ForStatement extends Statement {
        public final Optional<Statement> init;
        public final Optional<Expression> condition;
        public final Optional<Expression> update;
        public final Statement body;

        public ForStatement(Optional<Statement> init, Optional<Expression> condition,
                Optional<Expression> update, Statement body) {
            this.init = init;
            this.condition = condition;
            this.update = update;
            this.body = body;
        }

        @Override
        public String toString() {
            return "For: init=" + init.orElse(null) + ", cond=" + condition.orElse(null)
                    + ", update=" + update.orElse(null) + "\n    " + body.toString().replace("\n", "\n    ");
        }
    }

    public static class ForEachStatement extends Statement {
        public final TypeNode type;
        public final String variableName;
        public final Expression iterable;
        public final Statement body;

        public ForEachStatement(TypeNode type, String variableName, Expression iterable, Statement body) {
            this.type = type;
            this.variableName = variableName;
            this.iterable = iterable;
            this.body = body;
        }

        @Override
        public String toString() {
            return "ForEach: " + type + " " + variableName + " : " + iterable
                    + "\n    " + body.toString().replace("\n", "\n    ");
        }
    }

    public static class SwitchStatement extends Statement {
        public final Expression expression;
        public final List<SwitchGroup> groups;

        public SwitchStatement(Expression expression, List<SwitchGroup> groups) {
            this.expression = expression;
            this.groups = groups;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Switch: " + expression + "\n");
            for (SwitchGroup g : groups)
                sb.append("    ").append(g).append("\n");
            return sb.toString();
        }
    }

    public static class SwitchGroup extends Node {
        public final List<SwitchLabel> labels;
        public final List<Statement> statements;

        public SwitchGroup(List<SwitchLabel> labels, List<Statement> statements) {
            this.labels = labels;
            this.statements = statements;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (SwitchLabel l : labels)
                sb.append(l).append("\n");
            for (Statement s : statements)
                sb.append("        ").append(s.toString().replace("\n", "\n        ")).append("\n");
            return sb.toString();
        }
    }

    public interface SwitchLabel {
    }

    public static class CaseLabel implements SwitchLabel {
        public final Expression expression;

        public CaseLabel(Expression expression) {
            this.expression = expression;
        }

        @Override
        public String toString() {
            return "case " + expression + ":";
        }
    }

    public static class DefaultLabel implements SwitchLabel {
        @Override
        public String toString() {
            return "default:";
        }
    }

    public static class BreakStatement extends Statement {
        public final Optional<String> label;

        public BreakStatement(Optional<String> label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return "Break: " + label.orElse("");
        }
    }

    public static class ContinueStatement extends Statement {
        public final Optional<String> label;

        public ContinueStatement(Optional<String> label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return "Continue: " + label.orElse("");
        }
    }

    public static class ReturnStatement extends Statement {
        public final Optional<Expression> value;

        public ReturnStatement(Optional<Expression> value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "Return: " + value.orElse(null);
        }
    }

    public static class ThrowStatement extends Statement {
        public final Expression value;

        public ThrowStatement(Expression value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "Throw: " + value;
        }
    }

    public static class TryStatement extends Statement {
        public final BlockStatement tryBlock;
        public final List<CatchClause> catches;
        public final Optional<BlockStatement> finallyBlock;

        public TryStatement(BlockStatement tryBlock, List<CatchClause> catches, Optional<BlockStatement> finallyBlock) {
            this.tryBlock = tryBlock;
            this.catches = catches;
            this.finallyBlock = finallyBlock;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Try:\n    " + tryBlock.toString().replace("\n", "\n    "));
            for (CatchClause c : catches)
                sb.append("\n    ").append(c);
            if (finallyBlock.isPresent())
                sb.append("\n    Finally:\n    ").append(finallyBlock.get().toString().replace("\n", "\n    "));
            return sb.toString();
        }
    }

    public static class CatchClause extends Node {
        public final TypeNode type;
        public final String name;
        public final BlockStatement body;

        public CatchClause(TypeNode type, String name, BlockStatement body) {
            this.type = type;
            this.name = name;
            this.body = body;
        }

        @Override
        public String toString() {
            return "Catch: " + type + " " + name + "\n        " + body.toString().replace("\n", "\n        ");
        }
    }

    public static class AssertStatement extends Statement {
        public final Expression condition;
        public final Optional<Expression> message;

        public AssertStatement(Expression condition, Optional<Expression> message) {
            this.condition = condition;
            this.message = message;
        }

        @Override
        public String toString() {
            return "Assert: " + condition + (message.isPresent() ? ", msg=" + message.get() : "");
        }
    }

    public static class SynchronizedStatement extends Statement {
        public final Expression expression;
        public final BlockStatement body;

        public SynchronizedStatement(Expression expression, BlockStatement body) {
            this.expression = expression;
            this.body = body;
        }

        @Override
        public String toString() {
            return "Synchronized: " + expression + "\n    " + body.toString().replace("\n", "\n    ");
        }
    }

    public static class EmptyStatement extends Statement {
        @Override
        public String toString() {
            return "Empty";
        }
    }

    public static class LocalVariableDeclaration extends Statement {
        public final TypeNode type;
        public final List<VariableDeclarator> declarators;

        public LocalVariableDeclaration(TypeNode type, List<VariableDeclarator> declarators) {
            this.type = type;
            this.declarators = declarators;
        }

        @Override
        public String toString() {
            return "LocalVar: " + type + " " + declarators;
        }
    }

    public static class ExpressionStatement extends Statement {
        public final Expression expression;

        public ExpressionStatement(Expression expression) {
            this.expression = expression;
        }

        @Override
        public String toString() {
            return "ExprStmt: " + expression;
        }
    }

    public static class BinaryExpression extends Expression {
        public final Expression left;
        public final String operator;
        public final Expression right;

        public BinaryExpression(Expression left, String operator, Expression right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }

        @Override
        public String toString() {
            return "(" + left + " " + operator + " " + right + ")";
        }
    }

    public static class AssignmentExpression extends Expression {
        public final Expression left;
        public final String operator;
        public final Expression right;

        public AssignmentExpression(Expression left, String operator, Expression right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }

        @Override
        public String toString() {
            return "(" + left + " " + operator + " " + right + ")";
        }
    }

    public static class UnaryExpression extends Expression {
        public final String operator;
        public final Expression operand;

        public UnaryExpression(String operator, Expression operand) {
            this.operator = operator;
            this.operand = operand;
        }

        @Override
        public String toString() {
            return "(" + operator + " " + operand + ")";
        }
    }

    public static class PostfixExpression extends Expression {
        public final Expression expression;
        public final String operator;

        public PostfixExpression(Expression expression, String operator) {
            this.expression = expression;
            this.operator = operator;
        }

        @Override
        public String toString() {
            return "(" + expression + operator + ")";
        }
    }

    public static class CastExpression extends Expression {
        public final TypeNode type;
        public final Expression expression;

        public CastExpression(TypeNode type, Expression expression) {
            this.type = type;
            this.expression = expression;
        }

        @Override
        public String toString() {
            return "((" + type + ") " + expression + ")";
        }
    }

    public static class InstanceOfExpression extends Expression {
        public final Expression expression;
        public final TypeNode type;

        public InstanceOfExpression(Expression expression, TypeNode type) {
            this.expression = expression;
            this.type = type;
        }

        @Override
        public String toString() {
            return "(" + expression + " instanceof " + type + ")";
        }
    }

    public static class LiteralExpression extends Expression {
        public final String value;
        public final Tipo type;

        public LiteralExpression(String value, Tipo type) {
            this.value = value;
            this.type = type;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public static class IdentifierExpression extends Expression {
        public final String name;
        public final Optional<List<TypeNode>> typeArguments;

        public IdentifierExpression(String name, Optional<List<TypeNode>> typeArguments) {
            this.name = name;
            this.typeArguments = typeArguments;
        }

        @Override
        public String toString() {
            return name + (typeArguments.isPresent() ? "<" + typeArguments.get() + ">" : "");
        }
    }

    public static class ThisExpression extends Expression {
        @Override
        public String toString() {
            return "this";
        }
    }

    public static class SuperExpression extends Expression {
        @Override
        public String toString() {
            return "super";
        }
    }

    public static class ParenthesizedExpression extends Expression {
        public final Expression expression;

        public ParenthesizedExpression(Expression expression) {
            this.expression = expression;
        }

        @Override
        public String toString() {
            return "(" + expression + ")";
        }
    }

    public static class FieldAccessExpression extends Expression {
        public final Expression target;
        public final String field;

        public FieldAccessExpression(Expression target, String field) {
            this.target = target;
            this.field = field;
        }

        @Override
        public String toString() {
            return target + "." + field;
        }
    }

    public static class MethodInvocationExpression extends Expression {
        public final Expression target;
        public final String name;
        public final List<Expression> arguments;

        public MethodInvocationExpression(Expression target, String name, List<Expression> arguments) {
            this.target = target;
            this.name = name;
            this.arguments = arguments;
        }

        @Override
        public String toString() {
            String prefix = target != null ? target + "." : "";
            return prefix + (name != null ? name : "") + "(" + arguments + ")";
        }
    }

    public static class ArrayAccessExpression extends Expression {
        public final Expression array;
        public final Expression index;

        public ArrayAccessExpression(Expression array, Expression index) {
            this.array = array;
            this.index = index;
        }

        @Override
        public String toString() {
            return array + "[" + index + "]";
        }
    }

    public static class NewExpression extends Expression {
        public final TypeNode type;
        public final List<Expression> arguments;
        public final Optional<BlockStatement> body;

        public NewExpression(TypeNode type, List<Expression> arguments, Optional<BlockStatement> body) {
            this.type = type;
            this.arguments = arguments;
            this.body = body;
        }

        @Override
        public String toString() {
            return "new " + type + "(" + arguments + ")" + (body.isPresent() ? " { ... }" : "");
        }
    }

    public static class InnerNewExpression extends Expression {
        public final Expression enclosing;
        public final TypeNode type;
        public final List<Expression> arguments;
        public final Optional<BlockStatement> body;

        public InnerNewExpression(Expression enclosing, TypeNode type, List<Expression> arguments,
                Optional<BlockStatement> body) {
            this.enclosing = enclosing;
            this.type = type;
            this.arguments = arguments;
            this.body = body;
        }

        @Override
        public String toString() {
            return enclosing + ".new " + type + "(" + arguments + ")" + (body.isPresent() ? " { ... }" : "");
        }
    }

    public static class NewArrayExpression extends Expression {
        public final TypeNode type;
        public final List<Expression> dimensions;
        public final Optional<ArrayInitializer> initializer;

        public NewArrayExpression(TypeNode type, List<Expression> dimensions, Optional<ArrayInitializer> initializer) {
            this.type = type;
            this.dimensions = dimensions;
            this.initializer = initializer;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("new " + type);
            for (Expression dim : dimensions)
                sb.append("[").append(dim != null ? dim : "").append("]");
            if (initializer.isPresent())
                sb.append(" ").append(initializer.get());
            return sb.toString();
        }
    }

    public static class ArrayInitializer extends Expression {
        public final List<Expression> values;

        public ArrayInitializer(List<Expression> values) {
            this.values = values;
        }

        @Override
        public String toString() {
            return "{" + values + "}";
        }
    }

    public static class ConditionalExpression extends Expression {
        public final Expression condition;
        public final Expression thenExpr;
        public final Expression elseExpr;

        public ConditionalExpression(Expression condition, Expression thenExpr, Expression elseExpr) {
            this.condition = condition;
            this.thenExpr = thenExpr;
            this.elseExpr = elseExpr;
        }

        @Override
        public String toString() {
            return "(" + condition + " ? " + thenExpr + " : " + elseExpr + ")";
        }
    }

    public static class ClassLiteralExpression extends Expression {
        public final Expression type;

        public ClassLiteralExpression(Expression type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return type + ".class";
        }
    }

    // Dentro da classe Parser, adicione:
    public static class ThisConstructorCallExpression extends Expression {
        public final List<Expression> arguments;

        public ThisConstructorCallExpression(List<Expression> arguments) {
            this.arguments = arguments;
        }

        @Override
        public String toString() {
            return "this(" + arguments + ")";
        }
    }

    public static class SuperConstructorCallExpression extends Expression {
        public final List<Expression> arguments;

        public SuperConstructorCallExpression(List<Expression> arguments) {
            this.arguments = arguments;
        }

        @Override
        public String toString() {
            return "super(" + arguments + ")";
        }
    }
}
