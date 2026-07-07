package com.compiler.symbol;

import java.util.*;

public class SymbolEntry {
    
    public enum SymbolKind {
        CLASS("classe"),
        INTERFACE("interface"),
        FIELD("campo"),
        STATIC_FIELD("campo estático"),
        PARAMETER("parâmetro"),
        LOCAL_VARIABLE("variável local"),
        METHOD("método"),
        CONSTRUCTOR("construtor");
        
        private final String description;
        SymbolKind(String description) { this.description = description; }
        public String getDescription() { return description; }
    }
    
    public enum DataType {
        BOOLEAN("boolean", 1),
        BYTE("byte", 1),
        SHORT("short", 2),
        CHAR("char", 2),
        INT("int", 4),
        LONG("long", 8),
        FLOAT("float", 4),
        DOUBLE("double", 8),
        VOID("void", 0),
        STRING("String", 8),  // referência
        OBJECT("Object", 8),  // referência genérica
        ARRAY("array", 8);    // referência para array
        
        private final String name;
        private final int size;
        DataType(String name, int size) { this.name = name; this.size = size; }
        public String getName() { return name; }
        public int getSize() { return size; }
        
        public static DataType fromString(String type) {
            return switch (type.toLowerCase()) {
                case "boolean" -> BOOLEAN;
                case "byte" -> BYTE;
                case "short" -> SHORT;
                case "char" -> CHAR;
                case "int" -> INT;
                case "long" -> LONG;
                case "float" -> FLOAT;
                case "double" -> DOUBLE;
                case "void" -> VOID;
                case "string" -> STRING;
                default -> OBJECT;
            };
        }
    }
    
    public final String name;
    public final SymbolKind kind;
    public final DataType dataType;
    public final String customTypeName; // para tipos não primitivos
    public final int sizeInBytes;
    public final int memoryAddress;
    public final String scope;
    public final String initialValue;
    public final List<String> modifiers;
    public final int arrayDimensions;
    public final int line;
    public final int column;
    
    public SymbolEntry(String name, SymbolKind kind, DataType dataType, 
                      String customTypeName, int sizeInBytes, int memoryAddress,
                      String scope, String initialValue, List<String> modifiers,
                      int arrayDimensions, int line, int column) {
        this.name = name;
        this.kind = kind;
        this.dataType = dataType;
        this.customTypeName = customTypeName;
        this.sizeInBytes = sizeInBytes;
        this.memoryAddress = memoryAddress;
        this.scope = scope;
        this.initialValue = initialValue;
        this.modifiers = modifiers != null ? modifiers : new ArrayList<>();
        this.arrayDimensions = arrayDimensions;
        this.line = line;
        this.column = column;
    }
    
    public String getFullTypeName() {
        StringBuilder sb = new StringBuilder();
        if (customTypeName != null) {
            sb.append(customTypeName);
        } else {
            sb.append(dataType.getName());
        }
        for (int i = 0; i < arrayDimensions; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }
    
    public boolean isPrimitive() {
        return customTypeName == null && dataType != DataType.OBJECT && 
               dataType != DataType.STRING && dataType != DataType.ARRAY;
    }
    
    public boolean isReference() {
        return !isPrimitive();
    }
    
    public String getMemoryRegion() {
        return switch (kind) {
            case STATIC_FIELD -> "Área de Classe (Metaspace)";
            case LOCAL_VARIABLE, PARAMETER -> "Stack (Pilha de Execução)";
            case FIELD -> "Heap (dentro do objeto)";
            default -> "Heap";
        };
    }
    
    @Override
    public String toString() {
        return String.format(
            "%-20s | %-15s | %-15s | %6d bytes | 0x%08X | %-20s | %s",
            name,
            kind.getDescription(),
            getFullTypeName(),
            sizeInBytes,
            memoryAddress,
            scope,
            initialValue != null ? "= " + initialValue : ""
        ).trim();
    }
}