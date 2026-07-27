import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CallGraphGenerator {

    // --- Configuration ---
    private static final String SOURCE_PATH = "/home/user/project/src/main/java";
    private static final String ROOT_CLASS = "class_where_the_method_is";
    private static final String ROOT_METHOD = "method_to_start_analysis";
    private static final String PACKAGE_PREFIX = "com.examplepackage.";
    private static final int MAX_DEPTH = 32;
    private static final String OUTPUT_FILE = "call_graph.json";

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized",
            "return", "throw", "super", "this", "new"
    );

    private static final JavaProjectBuilder builder = new JavaProjectBuilder();

    public static void main(String[] args) {
        System.out.println("🚀 Indexing Java sources in: " + SOURCE_PATH + "...");
        builder.addSourceTree(new File(SOURCE_PATH));

        JavaClass rootClass = findClassByName(ROOT_CLASS);
        if (rootClass == null) {
            System.err.println("❌ Could not find root class matching name: " + ROOT_CLASS);
            return;
        }

        System.out.println("📦 Filtering calls to package prefix: '" + PACKAGE_PREFIX + "'");
        System.out.println("🔍 Building Call Graph starting at: " + rootClass.getFullyQualifiedName() + "." + ROOT_METHOD);

        CallNode tree = buildTree(rootClass.getFullyQualifiedName(), ROOT_METHOD, 0, new HashSet<>());

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            mapper.writeValue(new File(OUTPUT_FILE), tree);
            System.out.println("\n✅ Done! Call graph exported to: " + OUTPUT_FILE);
        } catch (IOException e) {
            System.err.println("❌ Error writing JSON output file: " + e.getMessage());
        }
    }

    private static CallNode buildTree(String fullyQualifiedClass, String methodName, int currentDepth, Set<String> visitedPath) {
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

        // --- INTERFACE RESOLUTION STEP ---
        JavaClass targetClass = cls;
        if (cls.isInterface() || cls.isAbstract()) {
            JavaClass concreteImpl = findConcreteImplementation(cls);
            if (concreteImpl != null) {
                node.resolvedImplementation = concreteImpl.getFullyQualifiedName();
                targetClass = concreteImpl; // Divert analysis to concrete class
            } else {
                node.status = cls.isInterface() ? "interface_endpoint_no_impl_found" : "abstract_class_no_impl_found";
                return node;
            }
        }

        JavaMethod method = findMethodInClassOrSuper(targetClass, methodName);
        if (method == null) {
            node.status = "method_not_found";
            return node;
        }

        if (method.getSourceCode() != null) {
            node.sourceCode = method.getSourceCode().trim();
        }

        if (method.isAbstract() || method.getSourceCode() == null) {
            node.status = "leaf_node_no_body";
            return node;
        }

        List<CalleeTarget> callees = parseCalleesFromSource(targetClass, method);

        for (CalleeTarget callee : callees) {
            CallNode childNode = buildTree(callee.fullyQualifiedClass, callee.methodName, currentDepth + 1, nextVisited);
            node.callees.add(childNode);
        }

        return node;
    }

    private static JavaClass findConcreteImplementation(JavaClass interfaceOrAbstract) {
        String interfaceFqn = interfaceOrAbstract.getFullyQualifiedName();

        // Convention Check: Class ending in 'Impl'
        JavaClass conventionImpl = builder.getClassByName(interfaceFqn + "Impl");
        if (conventionImpl != null && !conventionImpl.isInterface() && !conventionImpl.isAbstract()) {
            return conventionImpl;
        }

        for (JavaClass c : builder.getClasses()) {
            if (c.isInterface() || c.isAbstract()) {
                continue;
            }

            // Check implemented interfaces using JavaType (FQN comparison)
            for (JavaType implInterface : c.getInterfaces()) {
                if (implInterface.getFullyQualifiedName().equals(interfaceFqn)) {
                    return c;
                }
            }

            // Check superclasses for abstract base class inheritance
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

        // Use String (FQN) to prevent JavaType -> JavaClass conversion errors
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

        Pattern pattern = Pattern.compile("(?:([a-zA-Z0-9_]+)\\.)?([a-zA-Z0-9_]+)\\s*\\(");
        Matcher matcher = pattern.matcher(sourceCode);

        Set<String> processedInThisMethod = new HashSet<>();

        while (matcher.find()) {
            String targetVar = matcher.group(1);
            String targetMethod = matcher.group(2);

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

                String uniqueKey = targetFqn + "." + targetMethod;
                if (!processedInThisMethod.contains(uniqueKey)) {
                    processedInThisMethod.add(uniqueKey);
                    targets.add(new CalleeTarget(targetFqn, targetMethod));
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

    private static JavaMethod findMethodInClassOrSuper(JavaClass cls, String methodName) {
        for (JavaMethod m : cls.getMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        if (cls.getSuperJavaClass() != null) {
            return findMethodInClassOrSuper(cls.getSuperJavaClass(), methodName);
        }
        return null;
    }

    private static boolean isAllowedPackage(String fullyQualifiedName) {
        return PACKAGE_PREFIX != null && !PACKAGE_PREFIX.isEmpty()
                && fullyQualifiedName.startsWith(PACKAGE_PREFIX);
    }

    // --- Serialization Models ---
    public static class CallNode {
        public String fullyQualifiedSymbol;
        public String className;
        public String methodName;
        public String resolvedImplementation;
        public int depth;
        public String status;
        public String sourceCode;
        public List<CallNode> callees = new ArrayList<>();

        public CallNode(String fullyQualifiedSymbol, String className, String methodName, int depth) {
            this.fullyQualifiedSymbol = fullyQualifiedSymbol;
            this.className = className;
            this.methodName = methodName;
            this.depth = depth;
        }
    }

    private static class CalleeTarget {
        String fullyQualifiedClass;
        String methodName;

        CalleeTarget(String fullyQualifiedClass, String methodName) {
            this.fullyQualifiedClass = fullyQualifiedClass;
            this.methodName = methodName;
        }
    }
}
