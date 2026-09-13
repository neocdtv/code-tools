import java.util.List;

public class CalleeTarget {
    String fullyQualifiedClass;
    String methodName;
    List<String> argTypes;

    CalleeTarget(String fullyQualifiedClass, String methodName, List<String> argTypes) {
        this.fullyQualifiedClass = fullyQualifiedClass;
        this.methodName = methodName;
        this.argTypes = argTypes;
    }
}