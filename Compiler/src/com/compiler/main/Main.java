package com.compiler.main;

import com.compiler.lexer.Lexer;
import com.compiler.lexer.Token;
import com.compiler.parser.Parser;
import com.compiler.parser.Parser.Program;
import com.compiler.semantic.SemanticAnalyzer;
import com.compiler.symbol.SymbolTable;
import com.compiler.symbol.SymbolEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Main {
    
    // Códigos ANSI para cores
    public static final String VERDE = "\u001B[32m";
    public static final String VERMELHO = "\u001B[31m";
    public static final String RESET = "\u001B[0m";
    
    public static void main(String[] args) throws Exception {
        // Pega o caminho do arquivo (argumento ou padrão)
        String path = args.length > 0 ? args[0] : "teste_de_erro.txt";
        String src = Files.readString(Path.of(path));
        
        // Mostra o nome do arquivo
        System.out.println("█".repeat(80));
        System.out.println("█ COMPILADOR JAVA - ANÁLISE COMPLETA");
        System.out.println("█".repeat(80));
        System.out.println("█ Arquivo: " + Path.of(path).toAbsolutePath());
        System.out.println("█".repeat(80));
        
        // CÓDIGO FONTE
        System.out.println("\n=== CÓDIGO FONTE ===");
        System.out.println(src);
        
        // TOKENS (LEXER)
        System.out.println("\n=== TOKENS (LEXER) ===");
        Lexer lexer = new Lexer(src);
        List<Token> tokens = lexer.scan();
        for (Token t : tokens) {
            System.out.println(t);
        }
        
        try {
            // Cria o parser com os tokens (a tabela de símbolos é preenchida aqui)
            Parser parser = new Parser(tokens);
            Program program = parser.parseProgram();
            if (parser.hasErrors()) {
                System.out.println("Parser terminou com erro(s), mas recuperou e continuou a analise.");
                System.out.println("\n=== ERROS SINTATICOS ===");
                for (String error : parser.getErrors()) {
                    System.out.println("- " + error);
                }
            } else {
                System.out.println("Parser executado com sucesso!");
            }

            // Obtém a tabela de símbolos preenchida pelo parser
            SymbolTable symbolTable = parser.getSymbolTable();

            // Mostra a tabela de símbolos completa
            System.out.println("\n=== TABELA DE SÍMBOLOS ===");
            symbolTable.printFullTable();

            // Análise semântica (3ª fase), executada sobre a AST/tabela de símbolos
            // já produzidas mesmo que o parser tenha reportado erros sintáticos.
            SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer(program, parser.getDuplicateDeclarations());
            semanticAnalyzer.analyze();

            if (semanticAnalyzer.hasErrors()) {
                System.out.println("\n=== ERROS SEMANTICOS ===");
                for (String error : semanticAnalyzer.getErrors()) {
                    System.out.println("- " + error);
                }
            } else {
                System.out.println("\nAnalisador semântico executado sem erros!");
            }

            boolean hasAnyError = parser.hasErrors() || semanticAnalyzer.hasErrors();
            int totalErrors = parser.getErrors().size() + semanticAnalyzer.getErrors().size();

            if (hasAnyError) {
                System.out.println("\n" + VERMELHO + "✘ COMPILAÇÃO CONCLUÍDA COM ERROS!" + RESET);
                System.out.println(VERMELHO + "✘ " + totalErrors + " erro(s) encontrado(s) durante a análise." + RESET);
            } else {
                System.out.println("\n" + VERDE + "✔ COMPILAÇÃO CONCLUÍDA COM SUCESSO!" + RESET);
                System.out.println(VERDE + "✔ Nenhum erro encontrado durante a análise." + RESET);
            }

        } catch (Exception e) {
            System.err.println("❌ Erro no parser: " + e.getMessage());
        }
    }
}