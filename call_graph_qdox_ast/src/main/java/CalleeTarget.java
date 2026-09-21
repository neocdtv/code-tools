import java.util.List;
import java.util.Objects;

public class CalleeTarget {
    String fullyQualifiedClass;
    String methodName;
    List<String> argTypes;
    boolean inLoop;

    CalleeTarget(String fullyQualifiedClass, String methodName, List<String> argTypes, boolean inLoop) {
        this.fullyQualifiedClass = fullyQualifiedClass;
        this.methodName = methodName;
        this.argTypes = argTypes;
        this.inLoop = inLoop;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CalleeTarget)) return false;
        CalleeTarget that = (CalleeTarget) o;
        return Objects.equals(fullyQualifiedClass, that.fullyQualifiedClass) &&
                Objects.equals(methodName, that.methodName) &&
                Objects.equals(argTypes, that.argTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullyQualifiedClass, methodName, argTypes);
    }
}