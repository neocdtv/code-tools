package com.codetools;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Main {

    static class Task {
        File file;
        String methodName;
        Task(File file, String methodName) {
            this.file = file;
            this.methodName = methodName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Task task = (Task) o;
            return file.equals(task.file) && methodName.equals(task.methodName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, methodName);
        }
    }

    private static final Set<String> IGNORED_TYPES = Set.of(
            "List", "Set", "Map", "Collection", "Optional", "String", 
            "Integer", "Long", "Boolean", "Double", "Float", "BigDecimal", 
            "LocalDate", "LocalDateTime", "Object", "int", "long", "boolean", "double",
            "log", "logger", "LoggerFactory"
    );

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: mvn exec:java -Dexec.args=\"path/to/module/Class1.java methodName\"");
            return;
        }

        File initialFile = new File(args[0]);
        String initialMethodName = args[1];

        File projectRoot = findProjectRoot(initialFile);
        System.out.println("Detected Project Root: " + projectRoot.getAbsolutePath());

        Queue<Task> taskQueue = new ArrayDeque<>();
        Set<String> processedTasks = new HashSet<>();

        taskQueue.add(new Task(initialFile, initialMethodName));

        while (!taskQueue.isEmpty()) {
            Task currentTask = taskQueue.poll();
            String taskKey = currentTask.file.getAbsolutePath() + "#" + currentTask.methodName;
            if (!processedTasks.add(taskKey)) continue;

            try {
                CompilationUnit cu = StaticJavaParser.parse(currentTask.file);
                ClassOrInterfaceDeclaration parentClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                        .orElseThrow(() -> new RuntimeException("No class found in: " + currentTask.file.getName()));

                String originalClassName = parentClass.getNameAsString();
                MethodDeclaration initialMethod = parentClass.getMethodsByName(currentTask.methodName).stream()
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Method not found: " + currentTask.methodName + " in " + originalClassName));

                // 1. Gather local helper methods and cross-module calls (BFS)
                Set<MethodDeclaration> methodsToExtract = new LinkedHashSet<>();
                Queue<MethodDeclaration> localQueue = new ArrayDeque<>();
                methodsToExtract.add(initialMethod);
                localQueue.add(initialMethod);

                Set<String> referencedNames = new HashSet<>();

                while (!localQueue.isEmpty()) {
                    MethodDeclaration m = localQueue.poll();
                    
                    // Walk nodes to collect referenced variables/fields and names
                    m.walk(node -> {
                        if (node instanceof NameExpr) {
                            referencedNames.add(((NameExpr) node).getNameAsString());
                        }
                    });

                    for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                        boolean isLocal = call.getScope().isEmpty()
                                || call.getScope().get().isThisExpr()
                                || call.getScope().get().toString().equals(originalClassName);

                        if (isLocal) {
                            for (MethodDeclaration localMethod : parentClass.getMethodsByName(call.getNameAsString())) {
                                if (methodsToExtract.add(localMethod)) {
                                    localQueue.add(localMethod);
                                }
                            }
                        } else {
                            if (call.getScope().isPresent()) {
                                String scopeName = call.getScope().get().toString();
                                referencedNames.add(scopeName);

                                Optional<FieldDeclaration> matchedField = cu.findAll(FieldDeclaration.class).stream()
                                        .filter(f -> f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(scopeName)))
                                        .findFirst();

                                if (matchedField.isPresent()) {
                                    List<ClassOrInterfaceType> typeNodes = matchedField.get().findAll(ClassOrInterfaceType.class);
                                    if (!typeNodes.isEmpty()) {
                                        String baseTypeName = typeNodes.get(0).getNameAsString();

                                        if (!IGNORED_TYPES.contains(baseTypeName)) {
                                            File targetFile = findSourceFileAcrossModules(projectRoot, baseTypeName);
                                            if (targetFile != null) {
                                                Task nextTask = new Task(targetFile, call.getNameAsString());
                                                if (!processedTasks.contains(targetFile.getAbsolutePath() + "#" + call.getNameAsString())) {
                                                    taskQueue.add(nextTask);
                                                    System.out.println("Queued cross-module extraction: " + targetFile.getName() + " -> " + call.getNameAsString());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Resolve fields and imports
                Set<String> usedTypes = new HashSet<>();
                for (MethodDeclaration method : methodsToExtract) {
                    method.walk(node -> {
                        if (node instanceof ClassOrInterfaceType) {
                            usedTypes.add(((ClassOrInterfaceType) node).getNameAsString());
                        }
                    });
                }

                List<FieldDeclaration> requiredFields = cu.findAll(FieldDeclaration.class).stream()
                        .filter(field -> field.getVariables().stream().anyMatch(v -> referencedNames.contains(v.getNameAsString())))
                        .peek(field -> field.walk(node -> {
                            if (node instanceof ClassOrInterfaceType) {
                                usedTypes.add(((ClassOrInterfaceType) node).getNameAsString());
                            }
                        }))
                        .toList();

                List<ImportDeclaration> requiredImports = cu.getImports().stream()
                        .filter(imp -> usedTypes.stream().anyMatch(t -> imp.getNameAsString().endsWith("." + t) || imp.getNameAsString().equals(t)))
                        .toList();

                // 3. Build and write minimal standalone class
                CompilationUnit extractedCu = new CompilationUnit();
                cu.getPackageDeclaration().ifPresent(extractedCu::setPackageDeclaration);
                requiredImports.forEach(extractedCu::addImport);

                String newClassName = originalClassName + "_" + currentTask.methodName;
                ClassOrInterfaceDeclaration newClass = new ClassOrInterfaceDeclaration();
                newClass.setName(newClassName);
                newClass.setPublic(true);

                newClass.setComment(new BlockComment(String.format(
                        "\n * Source File: %s\n * Original Class: %s\n * Extracted Method: %s\n ",
                        currentTask.file.getName(), originalClassName, currentTask.methodName
                )));

                requiredFields.forEach(f -> newClass.addMember(f.clone()));
                methodsToExtract.forEach(m -> newClass.addMember(m.clone()));
                extractedCu.addType(newClass);

                File outputFile = new File(newClassName + ".java");
                try (FileWriter writer = new FileWriter(outputFile)) {
                    writer.write(extractedCu.toString());
                    System.out.println("Successfully generated: " + outputFile.getAbsolutePath());
                }

            } catch (Exception e) {
                System.err.println("Error processing task for " + currentTask.methodName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static File findProjectRoot(File file) {
        File current = file.getAbsoluteFile();
        File rootCandidate = current.getParentFile();
        while (current != null) {
            if (new File(current, "pom.xml").exists()) {
                rootCandidate = current;
            }
            current = current.getParentFile();
        }
        return rootCandidate;
    }

    private static File findSourceFileAcrossModules(File rootDir, String className) {
        try {
            return Files.walk(rootDir.toPath())
                    .filter(p -> p.toFile().isFile() && p.toFile().getName().equals(className + ".java"))
                    .map(Path::toFile)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
