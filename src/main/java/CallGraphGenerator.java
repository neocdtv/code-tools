import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CallGraphGenerator {

    // --- Configuration ---
    private static String PATH_PARAM_NAME = "path";
    private static String CLASS_PARAM_NAME = "class";
    private static String METHOD_PARAM_NAME = "method";
    private static String PACKAGE_PREFIX_PARAM_NAME = "package";
    private static String PATH = null;
    private static String CLASS = null;
    private static String METHOD = null;
    private static String PACKAGE_PREFIX = null;
    private static String OUTPUT_FILE = "call_graph.json";
    private static int MAX_DEPTH = 32;

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized",
            "return", "throw", "super", "this", "new"
    );

    private static final JavaProjectBuilder builder = new JavaProjectBuilder();

    public static void main(String[] args) {
        PATH = CliUtil.findCommandArgumentByName(PATH_PARAM_NAME, args);
        CLASS = CliUtil.findCommandArgumentByName(CLASS_PARAM_NAME, args);
        METHOD = CliUtil.findCommandArgumentByName(METHOD_PARAM_NAME, args);
        PACKAGE_PREFIX = CliUtil.findCommandArgumentByName(PACKAGE_PREFIX_PARAM_NAME, args);

        if (PATH == null || CLASS == null || METHOD == null || PACKAGE_PREFIX == null ) {
            printUsageAndExit();
        }

        System.out.println("🚀 Indexing Java sources in: " + PATH + "...");
        File sourceDir = new File(PATH);
        builder.addSourceTree(sourceDir);

        JavaClass rootClass = findClassByName(CLASS);
        if (rootClass == null) {
            System.err.println("❌ Could not find root class matching name: " + CLASS);
            return;
        }

        String commitId = getGitCommitId(sourceDir);
        System.out.println("📌 Git Commit ID: " + (commitId != null ? commitId : "UNKNOWN (Not a git repository)"));
        System.out.println("📦 Filtering calls to package prefix: '" + PACKAGE_PREFIX + "'");
        System.out.println("🔍 Building Call Graph starting at: " + rootClass.getFullyQualifiedName() + "." + METHOD);

        CallNode tree = buildTree(rootClass.getFullyQualifiedName(), METHOD, null, 0, new HashSet<>());

        // Wrap graph inside top-level metadata object
        CallGraphOutput outputWrapper = new CallGraphOutput(commitId, Instant.now().toString(), tree);

        ObjectMapper mapper = new ObjectMapper();

        // Force array elements onto individual new lines in exported JSON
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

        try {
            mapper.writer(prettyPrinter).writeValue(new File(OUTPUT_FILE), outputWrapper);
            System.out.println("\n✅ Done! Call graph exported to: " + OUTPUT_FILE);
        } catch (IOException e) {
            System.err.println("❌ Error writing JSON output file: " + e.getMessage());
        }
    }

    private static String getGitCommitId(File directory) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(directory)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            try (Scanner scanner = new Scanner(process.getInputStream())) {
                if (scanner.hasNext()) {
                    return scanner.next().trim();
                }
            }
        } catch (Exception e) {
            // Git command failed or directory is not a Git repo
        }
        return null;
    }

    private static void printUsageAndExit() {
        System.out.println("usage: java -jar target/call_trace.jar " +
                "-" + PATH_PARAM_NAME + "=... " +
                "-" + CLASS_PARAM_NAME + "=... " +
                "-" + METHOD_PARAM_NAME + "=... " +
                "-" + PACKAGE_PREFIX_PARAM_NAME + "=... "
        );
        System.exit(1);
    }

    private static CallNode buildTree(String fullyQualifiedClass, String methodName, List<String> expectedArgTypes, int currentDepth, Set<String> visitedPath) {
        String fullSymbol = fullyQualifiedClass + "." + methodName;
        CallNode node = new CallNode(fullSymbol, fullyQualifiedClass, methodName, currentDepth);

        if (currentDepth >= MAX_DEPTH) {
            node.status = "max_depth_reached";
            return node;
        }

        if (visitedPath.contains(fullSymbol)) {
            node.status = "circular_dependency_detected";
            return node;
        }

        Set<String> nextVisited = new HashSet<>(visitedPath);
        nextVisited.add(fullSymbol);

        JavaClass cls = builder.getClassByName(fullyQualifiedClass);
        if (cls == null) {
            node.status = "class_not_in_sources";
            return node;
        }

        // --- CAPTURE RELATIVE FILE PATH ---
        node.filePath = extractRelativeFilePath(cls);

        // --- CAPTURE CLASS ANNOTATIONS ---
        for (JavaAnnotation anno : cls.getAnnotations()) {
            node.classAnnotations.add(extractAnnotationNode(anno));
        }

        // --- INTERFACE RESOLUTION STEP ---
        JavaClass targetClass = cls;
        if (cls.isInterface() || cls.isAbstract()) {
            JavaClass concreteImpl = findConcreteImplementation(cls);
            if (concreteImpl != null) {
                node.resolvedImplementation = concreteImpl.getFullyQualifiedName();
                targetClass = concreteImpl;
                node.filePath = extractRelativeFilePath(concreteImpl);
            } else {
                node.status = cls.isInterface() ? "interface_endpoint_no_impl_found" : "abstract_class_no_impl_found";
                return node;
            }
        }

        JavaMethod method = findMethodInClassOrSuper(targetClass, methodName, expectedArgTypes);
        if (method == null) {
            node.status = "method_not_found";
            return node;
        }

        // --- CAPTURE MODIFIERS ---
        node.modifiers.addAll(method.getModifiers());

        // --- CAPTURE METHOD ANNOTATIONS ---
        for (JavaAnnotation anno : method.getAnnotations()) {
            node.methodAnnotations.add(extractAnnotationNode(anno));
        }

        // --- CAPTURE RETURN TYPE & PARAMETERS ---
        if (method.getReturnType() != null) {
            node.returnType = method.getReturnType().getGenericFullyQualifiedName();
        }

        for (JavaParameter param : method.getParameters()) {
            String paramType = param.getType() != null ? param.getType().getGenericFullyQualifiedName() : "Object";
            node.parameters.add(new ParameterNode(param.getName(), paramType));
        }

        // --- CAPTURE LINE NUMBERS & CLEANED SOURCE CODE ---
        node.startLine = method.getLineNumber();

        if (method.getSourceCode() != null) {
            String rawCode = method.getSourceCode().trim();
            String[] lines = rawCode.split("\\r?\\n");

            node.endLine = node.startLine + lines.length;

            if (lines.length > 1) {
                int baseIndent = Integer.MAX_VALUE;
                for (int i = 1; i < lines.length; i++) {
                    if (lines[i].isBlank()) continue;
                    int indent = 0;
                    while (indent < lines[i].length() && Character.isWhitespace(lines[i].charAt(indent))) {
                        indent++;
                    }
                    baseIndent = Math.min(baseIndent, indent);
                }

                if (baseIndent > 0 && baseIndent != Integer.MAX_VALUE) {
                    for (int i = 1; i < lines.length; i++) {
                        if (lines[i].length() >= baseIndent && lines[i].substring(0, baseIndent).isBlank()) {
                            lines[i] = lines[i].substring(baseIndent);
                        } else {
                            lines[i] = lines[i].stripLeading();
                        }
                    }
                }
            }

            node.sourceCode = Arrays.asList(lines);
        } else {
            node.endLine = node.startLine;
        }

        if (method.isAbstract() || method.getSourceCode() == null) {
            node.status = "leaf_node_no_body";
            return node;
        }

        List<CalleeTarget> callees = parseCalleesFromSource(targetClass, method);

        for (CalleeTarget callee : callees) {
            CallNode childNode = buildTree(callee.fullyQualifiedClass, callee.methodName, callee.argTypes, currentDepth + 1, nextVisited);
            node.callees.add(childNode);
        }

        return node;
    }

    private static String extractRelativeFilePath(JavaClass cls) {
        if (cls.getSource() == null || cls.getSource().getURL() == null) {
            return null;
        }
        try {
            Path basePath = Paths.get(PATH).toRealPath();
            Path filePath = Paths.get(cls.getSource().getURL().toURI()).toRealPath();
            return basePath.relativize(filePath).toString().replace('\\', '/');
        } catch (Exception e) {
            return cls.getSource().getURL().getPath();
        }
    }

    private static AnnotationNode extractAnnotationNode(JavaAnnotation anno) {
        AnnotationNode node = new AnnotationNode(anno.getType().getFullyQualifiedName());
        for (Map.Entry<String, Object> entry : anno.getNamedParameterMap().entrySet()) {
            node.properties.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return node;
    }

    private static JavaClass findConcreteImplementation(JavaClass interfaceOrAbstract) {
        String interfaceFqn = interfaceOrAbstract.getFullyQualifiedName();

        JavaClass conventionImpl = builder.getClassByName(interfaceFqn + "Impl");
        if (conventionImpl != null && !conventionImpl.isInterface() && !conventionImpl.isAbstract()) {
            return conventionImpl;
        }

        for (JavaClass c : builder.getClasses()) {
            if (c.isInterface() || c.isAbstract()) {
                continue;
            }

            for (JavaType implInterface : c.getInterfaces()) {
                if (implInterface.getFullyQualifiedName().equals(interfaceFqn)) {
                    return c;
                }
            }

            JavaClass superClass = c.getSuperJavaClass();
            while (superClass != null) {
                if (superClass.getFullyQualifiedName().equals(interfaceFqn)) {
                    return c;
                }
                superClass = superClass.getSuperJavaClass();
            }
        }

        return null;
    }

    private static List<CalleeTarget> parseCalleesFromSource(JavaClass declaringClass, JavaMethod method) {
        List<CalleeTarget> targets = new ArrayList<>();
        String sourceCode = method.getSourceCode();
        if (sourceCode == null) return targets;

        Map<String, String> typeMap = new HashMap<>();
        for (JavaField field : declaringClass.getFields()) {
            if (field.getType() != null) {
                typeMap.put(field.getName(), field.getType().getFullyQualifiedName());
            }
        }
        for (JavaParameter param : method.getParameters()) {
            if (param.getType() != null) {
                typeMap.put(param.getName(), param.getType().getFullyQualifiedName());
            }
        }

        Pattern pattern = Pattern.compile("(?:([a-zA-Z0-9_]+)\\.)?([a-zA-Z0-9_]+)\\s*\\(([^)]*)\\)");
        Matcher matcher = pattern.matcher(sourceCode);

        Set<String> processedInThisMethod = new HashSet<>();

        while (matcher.find()) {
            String targetVar = matcher.group(1);
            String targetMethod = matcher.group(2);
            String argsRaw = matcher.group(3);

            if (JAVA_KEYWORDS.contains(targetMethod)) {
                continue;
            }

            String targetFqn = null;

            if (targetVar == null || targetVar.equals("this")) {
                targetFqn = declaringClass.getFullyQualifiedName();
            } else if (typeMap.containsKey(targetVar)) {
                targetFqn = typeMap.get(targetVar);
            } else {
                JavaClass staticClass = findClassByName(targetVar);
                if (staticClass != null) {
                    targetFqn = staticClass.getFullyQualifiedName();
                }
            }

            if (targetFqn != null) {
                if (!isAllowedPackage(targetFqn)) {
                    continue;
                }

                List<String> argTypes = new ArrayList<>();
                if (argsRaw != null && !argsRaw.isBlank()) {
                    String[] splitArgs = argsRaw.split(",");
                    for (String arg : splitArgs) {
                        String argTrimmed = arg.trim();
                        if (typeMap.containsKey(argTrimmed)) {
                            argTypes.add(typeMap.get(argTrimmed));
                        } else {
                            argTypes.add("java.lang.Object");
                        }
                    }
                }

                String uniqueKey = targetFqn + "." + targetMethod + "(" + argTypes.size() + ")";
                if (!processedInThisMethod.contains(uniqueKey)) {
                    processedInThisMethod.add(uniqueKey);
                    targets.add(new CalleeTarget(targetFqn, targetMethod, argTypes));
                }
            }
        }

        return targets;
    }

    private static JavaClass findClassByName(String simpleOrFqn) {
        for (JavaClass cls : builder.getClasses()) {
            if (cls.getName().equals(simpleOrFqn) || cls.getFullyQualifiedName().equals(simpleOrFqn)) {
                return cls;
            }
        }
        return null;
    }

    private static JavaMethod findMethodInClassOrSuper(JavaClass cls, String methodName, List<String> expectedArgTypes) {
        List<JavaMethod> candidates = new ArrayList<>();
        for (JavaMethod m : cls.getMethods()) {
            if (m.getName().equals(methodName)) {
                candidates.add(m);
            }
        }

        if (!candidates.isEmpty()) {
            if (expectedArgTypes != null && candidates.size() > 1) {
                for (JavaMethod m : candidates) {
                    if (m.getParameters().size() == expectedArgTypes.size()) {
                        return m;
                    }
                }
            }
            return candidates.get(0);
        }

        if (cls.getSuperJavaClass() != null) {
            return findMethodInClassOrSuper(cls.getSuperJavaClass(), methodName, expectedArgTypes);
        }
        return null;
    }

    private static boolean isAllowedPackage(String fullyQualifiedName) {
        return PACKAGE_PREFIX != null && !PACKAGE_PREFIX.isEmpty()
                && fullyQualifiedName.startsWith(PACKAGE_PREFIX);
    }

    // --- Serialization Models ---
    public static class CallGraphOutput {
        public String gitCommitId;
        public String generatedAt;
        public CallNode callGraph;

        public CallGraphOutput(String gitCommitId, String generatedAt, CallNode callGraph) {
            this.gitCommitId = gitCommitId;
            this.generatedAt = generatedAt;
            this.callGraph = callGraph;
        }
    }

    public static class CallNode {
        public String fullyQualifiedSymbol;
        public String className;
        public String filePath;
        public String methodName;
        public List<String> modifiers = new ArrayList<>();
        public List<AnnotationNode> classAnnotations = new ArrayList<>();
        public List<AnnotationNode> methodAnnotations = new ArrayList<>();
        public String resolvedImplementation;
        public String returnType;
        public List<ParameterNode> parameters = new ArrayList<>();
        public int startLine;
        public int endLine;
        public int depth;
        public String status;
        public List<String> sourceCode;
        public List<CallNode> callees = new ArrayList<>();

        public CallNode(String fullyQualifiedSymbol, String className, String methodName, int depth) {
            this.fullyQualifiedSymbol = fullyQualifiedSymbol;
            this.className = className;
            this.methodName = methodName;
            this.depth = depth;
        }
    }

    public static class ParameterNode {
        public String name;
        public String type;

        public ParameterNode(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    public static class AnnotationNode {
        public String name;
        public Map<String, String> properties = new HashMap<>();

        public AnnotationNode(String name) {
            this.name = name;
        }
    }

    private static class CalleeTarget {
        String fullyQualifiedClass;
        String methodName;
        List<String> argTypes;

        CalleeTarget(String fullyQualifiedClass, String methodName, List<String> argTypes) {
            this.fullyQualifiedClass = fullyQualifiedClass;
            this.methodName = methodName;
            this.argTypes = argTypes;
        }
    }
}