import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SymbolSolverSetup {

    public static void initializeForMultiModuleProject(String projectRootPath) throws IOException {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();

        // 1. Add standard Java JRE classes (String, List, etc.)
        combinedTypeSolver.add(new ReflectionTypeSolver());

        // 2. Automatically find and add all module source directories
        Path projectRoot = Paths.get(projectRootPath);

        Files.walk(projectRoot)
                .filter(Files::isDirectory)
                // Look for both main and test source roots
                //.filter(path -> path.endsWith("src/main/java") || path.endsWith("src/test/java"))
                .filter(path -> path.endsWith("src/main/java"))
                .forEach(sourceRoot -> {
                    try {
                        // Add each module's source root to the solver
                        combinedTypeSolver.add(new JavaParserTypeSolver(sourceRoot.toFile()));
                        System.out.println("Added source root to solver: " + sourceRoot);
                    } catch (Exception e) {
                        System.err.println("Could not add source root: " + sourceRoot);
                    }
                });

        // 3. Configure JavaParser
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        StaticJavaParser.getParserConfiguration().setSymbolResolver(symbolSolver);
    }
}