package com.compiler.semantic;

import com.compiler.symbol.SymbolEntry;
import com.compiler.symbol.SymbolEntry.DataType;

import java.util.Objects;

/**
 * Representação de tipo usada pelo analisador semântico. Espelha a mesma
 * combinação (DataType base + nome de classe + dimensão de array) já usada
 * por SymbolEntry, para que os tipos derivados da tabela de símbolos e os
 * tipos inferidos a partir de expressões sejam diretamente comparáveis.
 */
public final class Type {

    public static final Type ERROR = new Type(null, null, 0, false, false, true);
    public static final Type NULL_TYPE = new Type(null, null, 0, false, true, false);
    public static final Type VOID = new Type(DataType.VOID, null, 0, false, false, false);

    public final DataType base;
    public final String className;
    public final int arrayDepth;
    public final boolean classRef;
    private final boolean nullType;
    private final boolean error;

    private Type(DataType base, String className, int arrayDepth, boolean classRef, boolean nullType,
            boolean error) {
        this.base = base;
        this.className = className;
        this.arrayDepth = arrayDepth;
        this.classRef = classRef;
        this.nullType = nullType;
        this.error = error;
    }

    public static Type of(DataType base) {
        return new Type(base, null, 0, false, false, false);
    }

    public static Type string() {
        return new Type(DataType.STRING, null, 0, false, false, false);
    }

    public static Type object(String className) {
        return new Type(DataType.OBJECT, className, 0, false, false, false);
    }

    /** Representa uma referência a um nome de classe (ex.: "System" em "System.out"), não um valor. */
    public static Type classRef(String className) {
        return new Type(DataType.OBJECT, className, 0, true, false, false);
    }

    public static Type fromEntry(SymbolEntry entry) {
        if (entry.customTypeName != null) {
            return new Type(DataType.OBJECT, entry.customTypeName, entry.arrayDimensions, false, false, false);
        }
        return new Type(entry.dataType, null, entry.arrayDimensions, false, false, false);
    }

    /** Constrói um Type a partir do nome de tipo declarado na AST (TypeNode.name) e sua dimensão de array. */
    public static Type fromDeclaration(String typeName, int arrayDepth) {
        if (isPrimitiveName(typeName)) {
            return new Type(DataType.fromString(typeName), null, arrayDepth, false, false, false);
        } else if (typeName.equals("String")) {
            return new Type(DataType.STRING, null, arrayDepth, false, false, false);
        } else if (typeName.equals("void")) {
            return new Type(DataType.VOID, null, arrayDepth, false, false, false);
        }
        return new Type(DataType.OBJECT, typeName, arrayDepth, false, false, false);
    }

    private static boolean isPrimitiveName(String name) {
        return switch (name) {
            case "boolean", "byte", "short", "char", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }

    public Type withArrayDepth(int newDepth) {
        return new Type(base, className, newDepth, classRef, nullType, error);
    }

    public Type elementType() {
        if (arrayDepth <= 0) {
            return ERROR;
        }
        return withArrayDepth(arrayDepth - 1);
    }

    public static Type numericResult(Type a, Type b) {
        if (a.base == DataType.DOUBLE || b.base == DataType.DOUBLE) return of(DataType.DOUBLE);
        if (a.base == DataType.FLOAT || b.base == DataType.FLOAT) return of(DataType.FLOAT);
        if (a.base == DataType.LONG || b.base == DataType.LONG) return of(DataType.LONG);
        return of(DataType.INT);
    }

    public boolean isError() {
        return error;
    }

    public boolean isNull() {
        return nullType;
    }

    public boolean isVoid() {
        return !error && !nullType && arrayDepth == 0 && base == DataType.VOID;
    }

    public boolean isPrimitive() {
        return !error && !nullType && arrayDepth == 0 && base != null
                && base != DataType.STRING && base != DataType.OBJECT && base != DataType.VOID;
    }

    public boolean isNumeric() {
        return isPrimitive() && base != DataType.BOOLEAN;
    }

    public boolean isBoolean() {
        return isPrimitive() && base == DataType.BOOLEAN;
    }

    public boolean isIntegral() {
        return isPrimitive() && (base == DataType.BYTE || base == DataType.SHORT
                || base == DataType.CHAR || base == DataType.INT || base == DataType.LONG);
    }

    public boolean isString() {
        return !error && !nullType && arrayDepth == 0 && base == DataType.STRING;
    }

    public boolean isArrayType() {
        return !error && !nullType && arrayDepth > 0;
    }

    public boolean isObjectType() {
        return !error && !nullType && arrayDepth == 0 && base == DataType.OBJECT;
    }

    public boolean isReference() {
        return isString() || isArrayType() || isObjectType();
    }

    private static int widenRank(DataType t) {
        return switch (t) {
            case BYTE -> 1;
            case SHORT, CHAR -> 2;
            case INT -> 3;
            case LONG -> 4;
            case FLOAT -> 5;
            case DOUBLE -> 6;
            default -> -1;
        };
    }

    /**
     * Compatibilidade de atribuição (this = valor de origem, target = variável/parâmetro de destino).
     * Segue as regras de widening numérico do Java de forma simplificada e não modela herança
     * entre classes definidas pelo usuário (apenas "Object" é tratado como supertipo universal).
     */
    public boolean isAssignableTo(Type target) {
        if (this.error || target.error) {
            return true;
        }
        if (target.isVoid()) {
            return false;
        }

        if (target.base == DataType.OBJECT && target.arrayDepth == 0 && "Object".equals(target.className)) {
            return this.nullType || this.isReference();
        }

        if (this.nullType) {
            return target.isReference();
        }

        if (target.arrayDepth > 0 || this.arrayDepth > 0) {
            return this.arrayDepth == target.arrayDepth
                    && this.base == target.base
                    && Objects.equals(this.className, target.className);
        }

        if (target.isString()) {
            return this.isString();
        }

        if (target.base == DataType.OBJECT) {
            return this.base == DataType.OBJECT && Objects.equals(this.className, target.className);
        }

        if (target.isBoolean()) {
            return this.isBoolean();
        }

        if (target.isNumeric()) {
            if (!this.isNumeric()) {
                return false;
            }
            if (this.base == target.base) {
                return true;
            }
            if (target.base == DataType.CHAR) {
                return false;
            }
            if (this.base == DataType.CHAR) {
                return widenRank(target.base) >= widenRank(DataType.INT);
            }
            return widenRank(this.base) <= widenRank(target.base);
        }

        return false;
    }

    public String describe() {
        if (error) {
            return "<tipo desconhecido>";
        }
        if (nullType) {
            return "null";
        }
        String name = className != null ? className : (base != null ? base.getName() : "?");
        StringBuilder sb = new StringBuilder(name);
        for (int i = 0; i < arrayDepth; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
