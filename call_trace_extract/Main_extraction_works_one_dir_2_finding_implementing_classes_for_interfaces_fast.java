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
        
        Map<File, Set<String>> fileToMethodNames = new LinkedHashMap<>();
        Map<File, CompilationUnit> fileToCu = new HashMap<>();

        taskQueue.add(new Task(initialFile, initialMethodName));

        while (!taskQueue.isEmpty()) {
            Task currentTask = taskQueue.poll();
            String taskKey = currentTask.file.getAbsolutePath() + "#" + currentTask.methodName;
            if (!processedTasks.add(taskKey)) continue;

            try {
                CompilationUnit cu = fileToCu.computeIfAbsent(currentTask.file, f -> {
                    try {
                        return StaticJavaParser.parse(f);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse: " + f.getName(), e);
                    }
                });

                ClassOrInterfaceDeclaration parentClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                        .orElseThrow(() -> new RuntimeException("No class found in: " + currentTask.file.getName()));

                String originalClassName = parentClass.getNameAsString();
                MethodDeclaration targetMethod = parentClass.getMethodsByName(currentTask.methodName).stream()
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Method not found: " + currentTask.methodName + " in " + originalClassName));

                fileToMethodNames.computeIfAbsent(currentTask.file, f -> new LinkedHashSet<>()).add(currentTask.methodName);

                Queue<MethodDeclaration> localQueue = new ArrayDeque<>();
                Set<MethodDeclaration> visitedLocalMethods = new HashSet<>();
                localQueue.add(targetMethod);
                visitedLocalMethods.add(targetMethod);

                while (!localQueue.isEmpty()) {
                    MethodDeclaration m = localQueue.poll();

                    for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                        boolean isLocal = call.getScope().isEmpty()
                                || call.getScope().get().isThisExpr()
                                || call.getScope().get().toString().equals(originalClassName);

                        if (isLocal) {
                            for (MethodDeclaration localMethod : parentClass.getMethodsByName(call.getNameAsString())) {
                                if (visitedLocalMethods.add(localMethod)) {
                                    localQueue.add(localMethod);
                                    fileToMethodNames.computeIfAbsent(currentTask.file, f -> new LinkedHashSet<>()).add(localMethod.getNameAsString());
                                }
                            }
                        } else {
                            if (call.getScope().isPresent()) {
                                String scopeName = call.getScope().get().toString();
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
                                                targetFile = resolveConcreteFileIfNeeded(projectRoot, targetFile, baseTypeName);
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

            } catch (Exception e) {
                System.err.println("Error processing task for " + currentTask.methodName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        for (Map.Entry<File, Set<String>> entry : fileToMethodNames.entrySet()) {
            File sourceFile = entry.getKey();
            Set<String> methodNames = entry.getValue();

            CompilationUnit cu = fileToCu.get(sourceFile);
            ClassOrInterfaceDeclaration parentClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
            String originalClassName = parentClass.getNameAsString();

            Set<MethodDeclaration> methodsToExtract = new LinkedHashSet<>();
            for (String mName : methodNames) {
                parentClass.getMethodsByName(mName).forEach(methodsToExtract::add);
            }

            Set<String> referencedNames = new HashSet<>();
            for (MethodDeclaration method : methodsToExtract) {
                method.walk(node -> {
                    if (node instanceof NameExpr) {
                        referencedNames.add(((NameExpr) node).getNameAsString());
                    }
                });
            }

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

            CompilationUnit extractedCu = new CompilationUnit();
            cu.getPackageDeclaration().ifPresent(extractedCu::setPackageDeclaration);
            requiredImports.forEach(extractedCu::addImport);

            String newClassName = originalClassName + "_extracted";
            ClassOrInterfaceDeclaration newClass = new ClassOrInterfaceDeclaration();
            newClass.setName(newClassName);
            newClass.setPublic(true);
            
            newClass.setInterface(parentClass.isInterface());
            parentClass.getTypeParameters().forEach(tp -> newClass.getTypeParameters().add(tp.clone()));
            parentClass.getExtendedTypes().forEach(et -> newClass.getExtendedTypes().add(et.clone()));
            parentClass.getImplementedTypes().forEach(it -> newClass.getImplementedTypes().add(it.clone()));

            List<String> methodDetails = new ArrayList<>();
            for (MethodDeclaration m : methodsToExtract) {
                int start = m.getBegin().map(p -> p.line).orElse(-1);
                int end = m.getEnd().map(p -> p.line).orElse(-1);
                methodDetails.add(String.format("%s (lines %d-%d)", m.getNameAsString(), start, end));
            }

            newClass.setComment(new BlockComment(String.format(
                    "\n * Source File: %s\n * Original Class: %s\n * Extracted Methods:\n *   - %s\n ",
                    sourceFile.getName(), originalClassName, String.join("\n *   - ", methodDetails)
            )));

            requiredFields.forEach(f -> newClass.addMember(f.clone()));
            
            for (MethodDeclaration m : methodsToExtract) {
                int start = m.getBegin().map(p -> p.line).orElse(-1);
                int end = m.getEnd().map(p -> p.line).orElse(-1);
                
                MethodDeclaration clonedMethod = m.clone();
                clonedMethod.setComment(new BlockComment(String.format(
                        "\n    * Original line numbers:\n    *   start: %d\n    *   end: %d\n    ", 
                        start, end
                )));
                newClass.addMember(clonedMethod);
            }

            extractedCu.addType(newClass);

            File targetDir = new File(".");
            if (extractedCu.getPackageDeclaration().isPresent()) {
                String packagePath = extractedCu.getPackageDeclaration().get().getNameAsString().replace('.', File.separatorChar);
                targetDir = new File(targetDir, packagePath);
            }
            targetDir.mkdirs();

            File outputFile = new File(targetDir, newClassName + ".java");
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(extractedCu.toString());
                System.out.println("Successfully generated: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Error writing output file for " + originalClassName + ": " + e.getMessage());
            }
        }
    }

    private static File resolveConcreteFileIfNeeded(File rootDir, File file, String typeName) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            Optional<ClassOrInterfaceDeclaration> classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class);
            if (classDecl.isPresent() && classDecl.get().isInterface()) {
                File implFile = findImplementingClassFile(rootDir, typeName);
                if (implFile != null) {
                    System.out.println("Resolved interface " + typeName + " to implementing class: " + implFile.getName());
                    return implFile;
                }
            }
        } catch (Exception e) {
            // fallback to original file if parsing fails
        }
        return file;
    }

    private static File findImplementingClassFile(File rootDir, String interfaceName) {
        try {
            return Files.walk(rootDir.toPath())
                    .filter(p -> p.toFile().isFile() && p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .filter(file -> {
                        try {
                            String content = Files.readString(file.toPath());
                            if (!content.contains(interfaceName)) {
                                return false;
                            }

                            CompilationUnit cu = StaticJavaParser.parse(file);
                            Optional<ClassOrInterfaceDeclaration> classDecl = cu.findFirst(ClassOrInterfaceDeclaration.class);
                            if (classDecl.isPresent() && !classDecl.get().isInterface()) {
                                return classDecl.get().getImplementedTypes().stream()
                                        .anyMatch(t -> t.getNameAsString().equals(interfaceName));
                            }
                        } catch (Exception e) {
                            // ignore parse errors for unrelated files
                        }
                        return false;
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
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
