package com.compiler.semantic;

import com.compiler.parser.Parser;
import com.compiler.parser.Parser.*;
import com.compiler.symbol.SymbolEntry;
import com.compiler.symbol.SymbolEntry.DataType;
import com.compiler.symbol.SymbolTable;

import java.util.*;

/**
 * Analisador Semântico (3ª fase do mini-compilador).
 *
 * Recebe a AST produzida pelo Parser e a tabela de símbolos já preenchida por
 * ele, e percorre a árvore validando:
 *
 * a) uso de variável não declarada;
 * b) variável/símbolo declarado duas vezes no mesmo escopo;
 * c) incompatibilidade de tipos em operadores;
 * d) tipos dos argumentos em chamadas de método/construtor;
 * e) atribuição de tipo incompatível a uma variável;
 * f) tipo da condição em estruturas de controle (if/while/do-while/for/for-each/switch).
 *
 * Limitações conhecidas (mini-compilador, documentadas propositalmente):
 * - não há resolução de sobrecarga de métodos/construtores (o parser já trata
 *   um segundo método com o mesmo nome como símbolo duplicado);
 * - não há modelagem de herança entre classes do usuário (apenas "Object" é
 *   tratado como supertipo universal de qualquer tipo referência);
 * - identificadores de classes da biblioteca padrão (System, Math, String, ...)
 *   são aceitos por uma whitelist, já que este compilador não modela imports.
 */
public class SemanticAnalyzer {

    private static final Set<String> KNOWN_BUILTIN_CLASSES = Set.of(
            "System", "Math", "Object", "String", "StringBuilder", "StringBuffer",
            "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character", "Number", "Void",
            "Exception", "RuntimeException", "Throwable", "Error", "Thread",
            "ArrayList", "LinkedList", "List", "Map", "HashMap", "TreeMap", "Set", "HashSet", "TreeSet",
            "Arrays", "Collections", "Scanner", "Comparable", "Iterable", "Iterator", "Optional", "Objects");

    private static final Set<String> ARITH_OPS = Set.of("+", "-", "*", "/", "%");
    private static final Set<String> SHIFT_OPS = Set.of("<<", ">>", ">>>");
    private static final Set<String> REL_OPS = Set.of("<", ">", "<=", ">=");
    private static final Set<String> EQ_OPS = Set.of("==", "!=");
    private static final Set<String> LOGICAL_OPS = Set.of("&&", "||");
    private static final Set<String> BIT_OPS = Set.of("&", "|", "^");

    private final Program program;
    private final List<Parser.DuplicateDeclaration> duplicateDeclarations;
    private final List<SemanticError> errors = new ArrayList<>();
    private final Map<String, SymbolTable> classScopesByName = new HashMap<>();

    private String currentClassName;
    private SymbolTable currentClassScope;
    private Type currentReturnType;

    public SemanticAnalyzer(Program program, List<Parser.DuplicateDeclaration> duplicateDeclarations) {
        this.program = program;
        this.duplicateDeclarations = duplicateDeclarations;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        List<String> out = new ArrayList<>();
        for (SemanticError e : errors) {
            out.add(e.format());
        }
        return out;
    }

    public void analyze() {
        collectClassScopes(program.types);
        reportDuplicateDeclarations();
        for (TypeDeclaration td : program.types) {
            visitTypeDeclaration(td);
        }
    }

    // ---------- Coleta prévia (necessária para resolver acesso a membros de outras classes) ----------

    private void collectClassScopes(List<TypeDeclaration> types) {
        for (TypeDeclaration td : types) {
            if (td instanceof ClassDeclaration cd) {
                classScopesByName.put(cd.name, cd.scope);
                collectNestedClassScopes(cd.members);
            } else if (td instanceof InterfaceDeclaration id) {
                classScopesByName.put(id.name, id.scope);
                collectNestedClassScopes(id.members);
            }
        }
    }

    private void collectNestedClassScopes(List<ClassMember> members) {
        for (ClassMember m : members) {
            if (m instanceof ClassDeclaration cd) {
                classScopesByName.put(cd.name, cd.scope);
                collectNestedClassScopes(cd.members);
            } else if (m instanceof InterfaceDeclaration id) {
                classScopesByName.put(id.name, id.scope);
                collectNestedClassScopes(id.members);
            }
        }
    }

    private void reportDuplicateDeclarations() {
        for (Parser.DuplicateDeclaration dup : duplicateDeclarations) {
            errors.add(new SemanticError(dup.line, dup.col,
                    "Símbolo '" + dup.name + "' já declarado no escopo '" + dup.scopePath + "'"));
        }
    }

    // ---------- Declarações ----------

    private void visitTypeDeclaration(TypeDeclaration td) {
        SymbolTable classScope;
        List<ClassMember> members;
        if (td instanceof ClassDeclaration cd) {
            classScope = cd.scope;
            members = cd.members;
        } else if (td instanceof InterfaceDeclaration id) {
            classScope = id.scope;
            members = id.members;
        } else {
            return;
        }

        String savedClassName = currentClassName;
        SymbolTable savedClassScope = currentClassScope;
        currentClassName = td.name;
        currentClassScope = classScope;

        for (ClassMember m : members) {
            visitClassMember(m);
        }

        currentClassName = savedClassName;
        currentClassScope = savedClassScope;
    }

    private void visitClassMember(ClassMember m) {
        if (m instanceof FieldDeclaration fd) {
            visitFieldDeclaration(fd);
        } else if (m instanceof MethodDeclaration md) {
            visitMethodDeclaration(md);
        } else if (m instanceof ConstructorDeclaration ctor) {
            visitConstructorDeclaration(ctor);
        } else if (m instanceof InitializerBlock ib) {
            Type savedReturn = currentReturnType;
            currentReturnType = Type.VOID;
            visitStatement(ib.body, currentClassScope);
            currentReturnType = savedReturn;
        } else if (m instanceof ClassDeclaration nested) {
            visitTypeDeclaration(nested);
        } else if (m instanceof InterfaceDeclaration nested) {
            visitTypeDeclaration(nested);
        }
        // ErrorMember: já reportado como erro sintático, nada a fazer aqui.
    }

    private void visitFieldDeclaration(FieldDeclaration fd) {
        for (VariableDeclarator decl : fd.declarators) {
            if (decl.initializer.isPresent()) {
                Type initType = visitExpression(decl.initializer.get(), currentClassScope);
                Type declaredType = Type.fromDeclaration(fd.type.name, fd.type.arrayDepth + decl.arrayDepth);
                checkAssignable(declaredType, initType, decl.initializer.get(), "o campo '" + decl.name + "'");
            }
        }
    }

    private void visitMethodDeclaration(MethodDeclaration md) {
        if (md.body == null) {
            return; // método abstrato, sem corpo para analisar
        }
        Type savedReturn = currentReturnType;
        currentReturnType = Type.fromDeclaration(md.returnType.name, md.returnType.arrayDepth);
        visitStatement(md.body, md.scope);
        currentReturnType = savedReturn;
    }

    private void visitConstructorDeclaration(ConstructorDeclaration ctor) {
        Type savedReturn = currentReturnType;
        currentReturnType = Type.VOID;
        visitStatement(ctor.body, ctor.scope);
        currentReturnType = savedReturn;
    }

    // ---------- Statements ----------

    private void visitStatement(Statement s, SymbolTable scope) {
        if (s == null) {
            return;
        }
        if (s instanceof BlockStatement b) {
            for (Statement st : b.statements) {
                visitStatement(st, scope);
            }
        } else if (s instanceof IfStatement ifs) {
            checkBooleanCondition(ifs.condition, scope, "if");
            visitStatement(ifs.thenBranch, scope);
            visitStatement(ifs.elseBranch, scope);
        } else if (s instanceof WhileStatement ws) {
            checkBooleanCondition(ws.condition, scope, "while");
            visitStatement(ws.body, scope);
        } else if (s instanceof DoWhileStatement dw) {
            visitStatement(dw.body, scope);
            checkBooleanCondition(dw.condition, scope, "do-while");
        } else if (s instanceof ForStatement fs) {
            fs.init.ifPresent(i -> visitStatement(i, scope));
            fs.condition.ifPresent(c -> checkBooleanCondition(c, scope, "for"));
            fs.update.ifPresent(u -> visitExpression(u, scope));
            visitStatement(fs.body, scope);
        } else if (s instanceof ForEachStatement fe) {
            visitForEach(fe, scope);
        } else if (s instanceof SwitchStatement sw) {
            visitSwitch(sw, scope);
        } else if (s instanceof ReturnStatement rs) {
            visitReturn(rs, scope);
        } else if (s instanceof ThrowStatement th) {
            visitExpression(th.value, scope);
        } else if (s instanceof TryStatement ts) {
            visitStatement(ts.tryBlock, scope);
            for (CatchClause cc : ts.catches) {
                visitStatement(cc.body, scope);
            }
            ts.finallyBlock.ifPresent(fb -> visitStatement(fb, scope));
        } else if (s instanceof AssertStatement as) {
            checkBooleanCondition(as.condition, scope, "assert");
            as.message.ifPresent(msg -> visitExpression(msg, scope));
        } else if (s instanceof SynchronizedStatement sy) {
            visitExpression(sy.expression, scope);
            visitStatement(sy.body, scope);
        } else if (s instanceof LocalVariableDeclaration lv) {
            visitLocalVarDecl(lv, scope);
        } else if (s instanceof ExpressionStatement es) {
            visitExpression(es.expression, scope);
        }
        // EmptyStatement, ErrorStatement, BreakStatement, ContinueStatement: nada a validar.
    }

    private void checkBooleanCondition(Expression cond, SymbolTable scope, String construct) {
        Type t = visitExpression(cond, scope);
        if (!t.isError() && !t.isBoolean()) {
            int[] pos = positionOf(cond);
            errors.add(new SemanticError(pos[0], pos[1],
                    "Condição de '" + construct + "' deve ser do tipo boolean, mas é '" + t.describe() + "'"));
        }
    }

    private void visitForEach(ForEachStatement fe, SymbolTable scope) {
        Type iterableType = visitExpression(fe.iterable, scope);
        Type loopVarType = Type.fromDeclaration(fe.type.name, fe.type.arrayDepth);

        if (!iterableType.isError()) {
            if (iterableType.isArrayType()) {
                Type elementType = iterableType.elementType();
                if (!elementType.isAssignableTo(loopVarType)) {
                    int[] pos = positionOf(fe.iterable);
                    errors.add(new SemanticError(pos[0], pos[1],
                            "Elemento do tipo '" + elementType.describe() + "' incompatível com a variável '"
                                    + fe.variableName + "' (" + loopVarType.describe() + ") do for-each"));
                }
            } else if (!iterableType.isReference()) {
                int[] pos = positionOf(fe.iterable);
                errors.add(new SemanticError(pos[0], pos[1],
                        "Tipo '" + iterableType.describe() + "' não é iterável em um for-each"));
            }
            // Tipos-objeto (não-array) não são validados: este mini-compilador não modela Iterable.
        }
        visitStatement(fe.body, scope);
    }

    private void visitSwitch(SwitchStatement sw, SymbolTable scope) {
        Type switchType = visitExpression(sw.expression, scope);
        if (!switchType.isError() && !(switchType.isIntegral() || switchType.isString())) {
            int[] pos = positionOf(sw.expression);
            errors.add(new SemanticError(pos[0], pos[1],
                    "Expressão de 'switch' deve ser int/char/String, mas é '" + switchType.describe() + "'"));
        }
        for (SwitchGroup g : sw.groups) {
            for (SwitchLabel lbl : g.labels) {
                if (lbl instanceof CaseLabel cl) {
                    Type caseType = visitExpression(cl.expression, scope);
                    if (!switchType.isError() && !caseType.isError() && !caseType.isAssignableTo(switchType)) {
                        int[] pos = positionOf(cl.expression);
                        errors.add(new SemanticError(pos[0], pos[1],
                                "Rótulo 'case' do tipo '" + caseType.describe()
                                        + "' incompatível com o tipo do switch ('" + switchType.describe() + "')"));
                    }
                }
            }
            for (Statement st : g.statements) {
                visitStatement(st, scope);
            }
        }
    }

    private void visitReturn(ReturnStatement rs, SymbolTable scope) {
        if (rs.value.isPresent()) {
            Type valueType = visitExpression(rs.value.get(), scope);
            if (currentReturnType != null && currentReturnType.isVoid()) {
                errors.add(new SemanticError(rs.line, rs.col, "Método 'void' não pode retornar um valor"));
            } else if (currentReturnType != null && !valueType.isError()
                    && !valueType.isAssignableTo(currentReturnType)) {
                errors.add(new SemanticError(rs.line, rs.col,
                        "Tipo de retorno incompatível: esperado '" + currentReturnType.describe()
                                + "', encontrado '" + valueType.describe() + "'"));
            }
        } else if (currentReturnType != null && !currentReturnType.isVoid()) {
            errors.add(new SemanticError(rs.line, rs.col,
                    "Método com retorno '" + currentReturnType.describe() + "' precisa retornar um valor"));
        }
    }

    private void visitLocalVarDecl(LocalVariableDeclaration lv, SymbolTable scope) {
        for (VariableDeclarator decl : lv.declarators) {
            if (decl.initializer.isPresent()) {
                Type initType = visitExpression(decl.initializer.get(), scope);
                Type declaredType = Type.fromDeclaration(lv.type.name, lv.type.arrayDepth + decl.arrayDepth);
                checkAssignable(declaredType, initType, decl.initializer.get(), "a variável '" + decl.name + "'");
            }
        }
    }

    private void checkAssignable(Type target, Type sourceType, Expression sourceExpr, String targetDescription) {
        if (target.isError() || sourceType.isError()) {
            return;
        }
        if (!sourceType.isAssignableTo(target)) {
            int[] pos = positionOf(sourceExpr);
            errors.add(new SemanticError(pos[0], pos[1],
                    "Não é possível atribuir um valor do tipo '" + sourceType.describe() + "' para "
                            + targetDescription + " do tipo '" + target.describe() + "'"));
        }
    }

    // ---------- Expressões ----------

    private Type visitExpression(Expression e, SymbolTable scope) {
        if (e == null) {
            return Type.ERROR;
        }
        if (e instanceof LiteralExpression lit) return literalType(lit);
        if (e instanceof IdentifierExpression id) return visitIdentifier(id, scope);
        if (e instanceof ThisExpression) return currentClassName != null ? Type.object(currentClassName) : Type.ERROR;
        if (e instanceof SuperExpression) return Type.ERROR;
        if (e instanceof BinaryExpression bin) return visitBinary(bin, scope);
        if (e instanceof AssignmentExpression asg) return visitAssignment(asg, scope);
        if (e instanceof UnaryExpression un) return visitUnary(un, scope);
        if (e instanceof PostfixExpression pf) return visitPostfix(pf, scope);
        if (e instanceof CastExpression cst) {
            visitExpression(cst.expression, scope);
            return Type.fromDeclaration(cst.type.name, cst.type.arrayDepth);
        }
        if (e instanceof InstanceOfExpression io) {
            visitExpression(io.expression, scope);
            return Type.of(DataType.BOOLEAN);
        }
        if (e instanceof ParenthesizedExpression p) return visitExpression(p.expression, scope);
        if (e instanceof ConditionalExpression c) return visitConditional(c, scope);
        if (e instanceof FieldAccessExpression fa) return visitFieldAccess(fa, scope);
        if (e instanceof MethodInvocationExpression mi) return visitMethodInvocation(mi, scope);
        if (e instanceof ArrayAccessExpression aa) return visitArrayAccess(aa, scope);
        if (e instanceof NewExpression ne) return visitNew(ne, scope);
        if (e instanceof NewArrayExpression nae) return visitNewArray(nae, scope);
        if (e instanceof ArrayInitializer ai) {
            for (Expression v : ai.values) {
                visitExpression(v, scope);
            }
            return Type.ERROR;
        }
        if (e instanceof ClassLiteralExpression) return Type.object("Class");
        if (e instanceof ThisConstructorCallExpression tc) {
            for (Expression a : tc.arguments) visitExpression(a, scope);
            return Type.VOID;
        }
        if (e instanceof SuperConstructorCallExpression sc) {
            for (Expression a : sc.arguments) visitExpression(a, scope);
            return Type.VOID;
        }
        if (e instanceof InnerNewExpression ine) {
            for (Expression a : ine.arguments) visitExpression(a, scope);
            return Type.object(ine.type.name);
        }
        return Type.ERROR;
    }

    private Type literalType(LiteralExpression lit) {
        return switch (lit.type) {
            case LITERAL_INTEIRO -> Type.of(DataType.INT);
            case LITERAL_LONG -> Type.of(DataType.LONG);
            case LITERAL_FLOAT -> Type.of(DataType.FLOAT);
            case LITERAL_DOUBLE -> Type.of(DataType.DOUBLE);
            case LITERAL_TEXTO -> Type.string();
            case LITERAL_CARACTERE -> Type.of(DataType.CHAR);
            case LITERAL_ESPECIAL -> switch (lit.value) {
                case "true", "false" -> Type.of(DataType.BOOLEAN);
                case "null" -> Type.NULL_TYPE;
                default -> Type.ERROR;
            };
            default -> Type.ERROR;
        };
    }

    private Type visitIdentifier(IdentifierExpression id, SymbolTable scope) {
        SymbolEntry entry = scope.lookup(id.name);
        if (entry != null) {
            if (entry.kind == SymbolEntry.SymbolKind.CLASS || entry.kind == SymbolEntry.SymbolKind.INTERFACE) {
                return Type.classRef(entry.name);
            }
            return Type.fromEntry(entry);
        }
        if (classScopesByName.containsKey(id.name) || KNOWN_BUILTIN_CLASSES.contains(id.name)) {
            return Type.classRef(id.name);
        }
        errors.add(new SemanticError(id.line, id.col, "Variável '" + id.name + "' não declarada"));
        return Type.ERROR;
    }

    private Type visitBinary(BinaryExpression bin, SymbolTable scope) {
        Type left = visitExpression(bin.left, scope);
        Type right = visitExpression(bin.right, scope);
        if (left.isError() || right.isError()) {
            return Type.ERROR;
        }
        String op = bin.operator;

        if (op.equals("+") && (left.isString() || right.isString())) {
            return Type.string();
        }
        if (ARITH_OPS.contains(op)) {
            if (left.isNumeric() && right.isNumeric()) {
                return Type.numericResult(left, right);
            }
            return incompatibleOperands(bin, op, left, right);
        }
        if (SHIFT_OPS.contains(op)) {
            if (left.isIntegral() && right.isIntegral()) {
                return Type.numericResult(left, Type.of(DataType.INT));
            }
            return incompatibleOperands(bin, op, left, right);
        }
        if (REL_OPS.contains(op)) {
            if (left.isNumeric() && right.isNumeric()) {
                return Type.of(DataType.BOOLEAN);
            }
            return incompatibleOperands(bin, op, left, right);
        }
        if (EQ_OPS.contains(op)) {
            if ((left.isNumeric() && right.isNumeric())
                    || (left.isBoolean() && right.isBoolean())
                    || left.isNull() || right.isNull()
                    || (left.isReference() && right.isReference())) {
                return Type.of(DataType.BOOLEAN);
            }
            return incompatibleOperands(bin, op, left, right);
        }
        if (LOGICAL_OPS.contains(op)) {
            if (left.isBoolean() && right.isBoolean()) {
                return Type.of(DataType.BOOLEAN);
            }
            return incompatibleOperands(bin, op, left, right);
        }
        if (BIT_OPS.contains(op)) {
            if (left.isBoolean() && right.isBoolean()) {
                return Type.of(DataType.BOOLEAN);
            }
            if (left.isIntegral() && right.isIntegral()) {
                return Type.numericResult(left, right);
            }
            return incompatibleOperands(bin, op, left, right);
        }
        return Type.ERROR;
    }

    private Type incompatibleOperands(BinaryExpression bin, String op, Type left, Type right) {
        int[] pos = positionOf(bin);
        errors.add(new SemanticError(pos[0], pos[1],
                "Operador '" + op + "' não pode ser aplicado a tipos incompatíveis '" + left.describe()
                        + "' e '" + right.describe() + "'"));
        return Type.ERROR;
    }

    private Type visitAssignment(AssignmentExpression asg, SymbolTable scope) {
        Type leftType = visitExpression(asg.left, scope);
        Type rightType = visitExpression(asg.right, scope);
        if (leftType.isError()) {
            return Type.ERROR;
        }
        if (rightType.isError()) {
            return leftType;
        }

        if (asg.operator.equals("=")) {
            if (!rightType.isAssignableTo(leftType)) {
                int[] pos = positionOf(asg);
                errors.add(new SemanticError(pos[0], pos[1],
                        "Não é possível atribuir um valor do tipo '" + rightType.describe() + "' para "
                                + describeAssignmentTarget(asg.left) + " do tipo '" + leftType.describe() + "'"));
            }
            return leftType;
        }

        String baseOp = asg.operator.substring(0, asg.operator.length() - 1);
        if (baseOp.equals("+") && (leftType.isString() || rightType.isString())) {
            return Type.string();
        }
        if (leftType.isNumeric() && rightType.isNumeric()) {
            return leftType; // atribuição composta sempre "recua" para o tipo da variável de destino
        }
        if (leftType.isBoolean() && rightType.isBoolean()
                && (baseOp.equals("&") || baseOp.equals("|") || baseOp.equals("^"))) {
            return Type.of(DataType.BOOLEAN);
        }
        int[] pos = positionOf(asg);
        errors.add(new SemanticError(pos[0], pos[1],
                "Operador '" + asg.operator + "' não pode ser aplicado a tipos incompatíveis '"
                        + leftType.describe() + "' e '" + rightType.describe() + "'"));
        return Type.ERROR;
    }

    private String describeAssignmentTarget(Expression left) {
        if (left instanceof IdentifierExpression id) return "a variável '" + id.name + "'";
        if (left instanceof FieldAccessExpression fa) return "o campo '" + fa.field + "'";
        if (left instanceof ArrayAccessExpression) return "o elemento do array";
        return "o destino da atribuição";
    }

    private Type visitUnary(UnaryExpression un, SymbolTable scope) {
        Type operand = visitExpression(un.operand, scope);
        if (operand.isError()) {
            return Type.ERROR;
        }
        return switch (un.operator) {
            case "!" -> operand.isBoolean() ? Type.of(DataType.BOOLEAN) : incompatibleUnary(un, operand);
            case "~" -> operand.isIntegral() ? operand : incompatibleUnary(un, operand);
            case "+", "-", "++", "--" -> operand.isNumeric() ? operand : incompatibleUnary(un, operand);
            default -> Type.ERROR;
        };
    }

    private Type visitPostfix(PostfixExpression pf, SymbolTable scope) {
        Type operand = visitExpression(pf.expression, scope);
        if (operand.isError()) {
            return Type.ERROR;
        }
        if (!operand.isNumeric()) {
            int[] pos = positionOf(pf);
            errors.add(new SemanticError(pos[0], pos[1],
                    "Operador '" + pf.operator + "' não pode ser aplicado ao tipo '" + operand.describe() + "'"));
            return Type.ERROR;
        }
        return operand;
    }

    private Type incompatibleUnary(UnaryExpression un, Type operand) {
        int[] pos = positionOf(un);
        errors.add(new SemanticError(pos[0], pos[1],
                "Operador unário '" + un.operator + "' não pode ser aplicado ao tipo '" + operand.describe() + "'"));
        return Type.ERROR;
    }

    private Type visitConditional(ConditionalExpression c, SymbolTable scope) {
        Type condType = visitExpression(c.condition, scope);
        if (!condType.isError() && !condType.isBoolean()) {
            int[] pos = positionOf(c.condition);
            errors.add(new SemanticError(pos[0], pos[1],
                    "Condição do operador ternário deve ser do tipo boolean, mas é '" + condType.describe() + "'"));
        }
        Type thenType = visitExpression(c.thenExpr, scope);
        Type elseType = visitExpression(c.elseExpr, scope);
        if (thenType.isError() || elseType.isError()) {
            return Type.ERROR;
        }
        if (thenType.isNumeric() && elseType.isNumeric()) {
            return Type.numericResult(thenType, elseType);
        }
        if (elseType.isAssignableTo(thenType)) {
            return thenType;
        }
        if (thenType.isAssignableTo(elseType)) {
            return elseType;
        }
        int[] pos = positionOf(c);
        errors.add(new SemanticError(pos[0], pos[1],
                "Ramos do operador ternário têm tipos incompatíveis: '" + thenType.describe() + "' e '"
                        + elseType.describe() + "'"));
        return Type.ERROR;
    }

    private SymbolTable resolveClassScope(Type t) {
        if (t.base == DataType.OBJECT && t.arrayDepth == 0 && t.className != null) {
            return classScopesByName.get(t.className);
        }
        return null;
    }

    private Type visitFieldAccess(FieldAccessExpression fa, SymbolTable scope) {
        Type targetType = visitExpression(fa.target, scope);
        if (targetType.isError()) {
            return Type.ERROR;
        }
        SymbolTable targetClassScope = resolveClassScope(targetType);
        if (targetClassScope == null) {
            return Type.ERROR; // classe externa/desconhecida (ex.: System.out) - não validamos
        }
        SymbolEntry entry = targetClassScope.lookupCurrentScope(fa.field);
        if (entry == null) {
            errors.add(new SemanticError(fa.line, fa.col,
                    "Campo '" + fa.field + "' não existe na classe '" + targetType.className + "'"));
            return Type.ERROR;
        }
        return Type.fromEntry(entry);
    }

    private Type visitMethodInvocation(MethodInvocationExpression mi, SymbolTable scope) {
        List<Type> argTypes = new ArrayList<>();
        for (Expression arg : mi.arguments) {
            argTypes.add(visitExpression(arg, scope));
        }

        SymbolTable targetClassScope;
        String targetClassLabel;
        if (mi.target == null) {
            targetClassScope = currentClassScope;
            targetClassLabel = currentClassName;
        } else {
            Type targetType = visitExpression(mi.target, scope);
            if (targetType.isError()) {
                return Type.ERROR;
            }
            targetClassScope = resolveClassScope(targetType);
            targetClassLabel = targetType.className;
            if (targetClassScope == null) {
                return Type.ERROR; // classe externa/desconhecida - não validamos (ex.: System.out.println)
            }
        }
        if (targetClassScope == null) {
            return Type.ERROR;
        }

        SymbolEntry methodEntry = targetClassScope.lookupCurrentScope(mi.name);
        if (methodEntry == null || methodEntry.kind != SymbolEntry.SymbolKind.METHOD) {
            errors.add(new SemanticError(mi.line, mi.col,
                    "Método '" + mi.name + "' não existe na classe '" + targetClassLabel + "'"));
            return Type.ERROR;
        }

        List<SymbolEntry> params = collectParameters(targetClassScope, mi.name);
        checkArguments(mi.name, params, argTypes, mi.arguments, mi.line, mi.col);

        return Type.fromEntry(methodEntry);
    }

    private List<SymbolEntry> collectParameters(SymbolTable classScope, String memberName) {
        for (SymbolTable child : classScope.getChildren()) {
            if (child.getScopeName().equals(memberName)) {
                List<SymbolEntry> params = new ArrayList<>();
                for (SymbolEntry e : child.getAllSymbols()) {
                    if (e.kind == SymbolEntry.SymbolKind.PARAMETER) {
                        params.add(e);
                    }
                }
                return params;
            }
        }
        return Collections.emptyList();
    }

    private void checkArguments(String memberName, List<SymbolEntry> params, List<Type> argTypes,
            List<Expression> argExprs, int line, int col) {
        if (params.size() != argTypes.size()) {
            errors.add(new SemanticError(line, col,
                    "Número de argumentos incorreto para '" + memberName + "': esperado " + params.size()
                            + ", encontrado " + argTypes.size()));
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            Type paramType = Type.fromEntry(params.get(i));
            Type argType = argTypes.get(i);
            if (argType.isError()) {
                continue;
            }
            if (!argType.isAssignableTo(paramType)) {
                int[] pos = positionOf(argExprs.get(i));
                errors.add(new SemanticError(pos[0], pos[1],
                        "Argumento " + (i + 1) + " de '" + memberName + "' tem tipo '" + argType.describe()
                                + "', esperado '" + paramType.describe() + "'"));
            }
        }
    }

    private Type visitArrayAccess(ArrayAccessExpression aa, SymbolTable scope) {
        Type arrayType = visitExpression(aa.array, scope);
        Type indexType = visitExpression(aa.index, scope);
        if (!indexType.isError() && !indexType.isIntegral()) {
            int[] pos = positionOf(aa.index);
            errors.add(new SemanticError(pos[0], pos[1],
                    "Índice de array deve ser inteiro, mas é '" + indexType.describe() + "'"));
        }
        if (arrayType.isError()) {
            return Type.ERROR;
        }
        if (!arrayType.isArrayType()) {
            int[] pos = positionOf(aa.array);
            errors.add(new SemanticError(pos[0], pos[1],
                    "Tipo '" + arrayType.describe() + "' não é um array"));
            return Type.ERROR;
        }
        return arrayType.elementType();
    }

    private Type visitNew(NewExpression ne, SymbolTable scope) {
        List<Type> argTypes = new ArrayList<>();
        for (Expression a : ne.arguments) {
            argTypes.add(visitExpression(a, scope));
        }
        SymbolTable classScope = classScopesByName.get(ne.type.name);
        if (classScope != null) {
            if (hasExplicitConstructor(classScope)) {
                List<SymbolEntry> params = collectParameters(classScope, ne.type.name);
                checkArguments(ne.type.name, params, argTypes, ne.arguments, ne.line, ne.col);
            } else if (!ne.arguments.isEmpty()) {
                errors.add(new SemanticError(ne.line, ne.col,
                        "Classe '" + ne.type.name + "' não possui construtor com argumentos (usa construtor padrão)"));
            }
        }
        return Type.object(ne.type.name);
    }

    private boolean hasExplicitConstructor(SymbolTable classScope) {
        for (SymbolEntry e : classScope.getAllSymbols()) {
            if (e.kind == SymbolEntry.SymbolKind.CONSTRUCTOR) {
                return true;
            }
        }
        return false;
    }

    private Type visitNewArray(NewArrayExpression nae, SymbolTable scope) {
        for (Expression dim : nae.dimensions) {
            if (dim == null) {
                continue;
            }
            Type dimType = visitExpression(dim, scope);
            if (!dimType.isError() && !dimType.isIntegral()) {
                int[] pos = positionOf(dim);
                errors.add(new SemanticError(pos[0], pos[1],
                        "Dimensão de array deve ser inteira, mas é '" + dimType.describe() + "'"));
            }
        }
        if (nae.initializer.isPresent()) {
            for (Expression v : nae.initializer.get().values) {
                visitExpression(v, scope);
            }
        }
        Type base = Type.fromDeclaration(nae.type.name, nae.type.arrayDepth);
        return base.withArrayDepth(base.arrayDepth + nae.dimensions.size());
    }

    // ---------- Posição ----------

    private int[] positionOf(Expression e) {
        Expression cur = e;
        while (cur != null) {
            if (cur.line > 0) {
                return new int[] { cur.line, cur.col };
            }
            if (cur instanceof ParenthesizedExpression p) {
                cur = p.expression;
            } else {
                break;
            }
        }
        return new int[] { 0, 0 };
    }
}
