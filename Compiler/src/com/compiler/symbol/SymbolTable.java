package com.compiler.symbol;

import java.util.*;

public class SymbolTable {

    private final SymbolTable parent;
    private final String scopeName;
    private final Map<String, SymbolEntry> symbols;
    private final List<SymbolTable> children;
    private int nextMemoryAddress;
    
    public SymbolTable(SymbolTable parent, String scopeName) {
        this.parent = parent;
        this.scopeName = scopeName;
        this.symbols = new LinkedHashMap<>();
        this.children = new ArrayList<>();
        
        if (parent != null) {
            parent.children.add(this);
            this.nextMemoryAddress = parent.nextMemoryAddress;
        } else {
            this.nextMemoryAddress = 0x1000;
        }
    }
    
    /**
     * Tenta adicionar o símbolo ao escopo atual. Retorna false se já existir
     * um símbolo com o mesmo nome neste escopo (o chamador decide como reportar
     * a duplicata - normalmente via análise semântica).
     */
    public boolean addSymbol(SymbolEntry entry) {
        if (symbols.containsKey(entry.name)) {
            return false;
        }
        symbols.put(entry.name, entry);
        nextMemoryAddress += entry.sizeInBytes;
        return true;
    }
    
    public SymbolEntry lookup(String name) {
        if (symbols.containsKey(name)) {
            return symbols.get(name);
        }
        if (parent != null) {
            return parent.lookup(name);
        }
        return null;
    }
    
    public SymbolEntry lookupCurrentScope(String name) {
        return symbols.get(name);
    }
    
    public String getFullScopePath() {
        if (parent == null || parent.scopeName.equals("global")) {
            return scopeName;
        }
        return parent.getFullScopePath() + "." + scopeName;
    }
    
    public int allocateAddress(int size) {
        int address = nextMemoryAddress;
        nextMemoryAddress += size;
        return address;
    }
    
    public SymbolTable getParent() { return parent; }
    public String getScopeName() { return scopeName; }
    public Collection<SymbolEntry> getAllSymbols() { return symbols.values(); }
    public List<SymbolTable> getChildren() { return children; }
    public int getNextMemoryAddress() { return nextMemoryAddress; }
    
    /**
     * Imprime a tabela de símbolos completa no formato especificado
     */
    public void printFullTable() {
        System.out.println("\n" + "═".repeat(160));
        System.out.println("TABELA DE SÍMBOLOS COMPLETA");
        System.out.println("═".repeat(160));
        
        // Cabeçalho
        System.out.printf("%-15s | %-20s | %-6s | %-18s | %-12s | %-18s | %-35s | %-15s | %-12s | %-7s | %-12s | %-8s | %-30s | %-25s | %-10s%n",
            "Token", "Lexema", "Linha", "Categoria", "Tipo de dado", "Tipo de variável",
            "Escopo", "Valor", "Endereço", "Tamanho", "Inicializado", "Dim.",
            "Parâmetros", "Modificadores", "Retorno");
        System.out.println("─".repeat(160));
        
        // Imprime símbolos recursivamente
        printFormattedTableRecursive();
    }
    
    private void printFormattedTableRecursive() {
        for (SymbolEntry entry : symbols.values()) {
            printSymbolEntry(entry);
        }
        for (SymbolTable child : children) {
            child.printFormattedTableRecursive();
        }
    }
    
    private void printSymbolEntry(SymbolEntry entry) {
        // Determina o Token
        String token = getTokenFromKind(entry.kind);
        
        // Determina o Tipo de variável
        String tipoVariavel = getVariableType(entry.kind);
        
        // Determina se está inicializado
        String inicializado = (entry.initialValue != null && !entry.initialValue.isEmpty()) ? "true" : "false";
        
        // Formata o endereço
        String endereco = String.format("0x%08X", entry.memoryAddress);
        
        // Formata o tamanho
        String tamanho = entry.sizeInBytes + " bytes";
        
        // Formata o valor
        String valor = (entry.initialValue != null) ? entry.initialValue : "-";
        
        // Formata os parâmetros (apenas para métodos)
        String parametros = "-";
        if (entry.kind == SymbolEntry.SymbolKind.METHOD || entry.kind == SymbolEntry.SymbolKind.CONSTRUCTOR) {
            // Procura os parâmetros no escopo filho
            parametros = getMethodParameters(entry.name);
        }
        
        // Formata os modificadores
        String modificadores = entry.modifiers != null && !entry.modifiers.isEmpty() 
            ? String.join(", ", entry.modifiers) 
            : "-";
        
        // Formata o retorno (apenas para métodos)
        String retorno = "-";
        if (entry.kind == SymbolEntry.SymbolKind.METHOD) {
            retorno = entry.getFullTypeName();
            if (retorno.equals("void")) {
                retorno = "void";
            }
        } else if (entry.kind == SymbolEntry.SymbolKind.CONSTRUCTOR) {
            retorno = entry.customTypeName != null ? entry.customTypeName : "-";
        }
        
        // Imprime a linha formatada
        System.out.printf("%-15s | %-20s | %-6d | %-18s | %-12s | %-18s | %-35s | %-15s | %-12s | %-7s | %-12s | %-8d | %-30s | %-25s | %-10s%n",
            token,
            entry.name,
            entry.line,
            entry.kind.getDescription(),
            entry.getFullTypeName(),
            tipoVariavel,
            entry.scope,
            valor,
            endereco,
            tamanho,
            inicializado,
            entry.arrayDimensions,
            parametros,
            modificadores,
            retorno);
    }
    
    private String getTokenFromKind(SymbolEntry.SymbolKind kind) {
        return switch (kind) {
            case CLASS -> "CLASS";
            case INTERFACE -> "INTERFACE";
            case FIELD -> "FIELD";
            case STATIC_FIELD -> "STATIC_FIELD";
            case PARAMETER -> "PARAMETER";
            case LOCAL_VARIABLE -> "LOCAL_VARIABLE";
            case METHOD -> "METHOD";
            case CONSTRUCTOR -> "CONSTRUCTOR";
        };
    }
    
    private String getVariableType(SymbolEntry.SymbolKind kind) {
        return switch (kind) {
            case FIELD -> "atributo da classe";
            case STATIC_FIELD -> "atributo estático";
            case PARAMETER -> "parâmetro";
            case LOCAL_VARIABLE -> "variável local";
            default -> "-";
        };
    }
    
    private String getMethodParameters(String methodName) {
        // Procura nos escopos filhos pelo escopo do método
        for (SymbolTable child : children) {
            if (child.scopeName.equals(methodName)) {
                List<String> params = new ArrayList<>();
                for (SymbolEntry entry : child.symbols.values()) {
                    if (entry.kind == SymbolEntry.SymbolKind.PARAMETER) {
                        StringBuilder param = new StringBuilder();
                        param.append(entry.getFullTypeName());
                        param.append(" ").append(entry.name);
                        if (entry.arrayDimensions > 0) {
                            for (int i = 0; i < entry.arrayDimensions; i++) {
                                param.append("[]");
                            }
                        }
                        params.add(param.toString());
                    }
                }
                if (!params.isEmpty()) {
                    return "(" + String.join(", ", params) + ")";
                }
            }
        }
        return "()";
    }
    
    public List<SymbolEntry> collectAllSymbols() {
        List<SymbolEntry> all = new ArrayList<>();
        collectAllRecursive(all);
        return all;
    }
    
    private void collectAllRecursive(List<SymbolEntry> all) {
        all.addAll(symbols.values());
        for (SymbolTable child : children) {
            child.collectAllRecursive(all);
        }
    }
    
    public void printTable(String indent) {
        System.out.println(indent + "┌─ Escopo: " + getFullScopePath());
        for (SymbolEntry entry : symbols.values()) {
            System.out.println(indent + "│ " + entry);
        }
        if (symbols.isEmpty()) {
            System.out.println(indent + "│ (vazio)");
        }
        System.out.println(indent + "└─ " + symbols.size() + " símbolo(s)");
    }
}
