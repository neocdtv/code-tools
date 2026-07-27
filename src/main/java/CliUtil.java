import java.util.ArrayList;
import java.util.List;

/**
 * @author xix
 */
public class CliUtil {

    static String findCommandArgumentByName(final String argNameToFind, final String[] args) {
        String argValue = null;
        for (String argToCheck : args) {
            final String[] split = argToCheck.split("=");
            if (split[0].equals(argNameToFind)) {
                argValue = split[1];
            }
        }
        return argValue;
    }

    static List<String> findCommandArgumentsByName(final String argNameToFind, final String[] args) {
        final List<String> argValue = new ArrayList<>();
        for (String argToCheck : args) {
            final String[] split = argToCheck.split("=");
            if (split[0].equals(argNameToFind)) {
                argValue.add(split[1]);
            }
        }
        return argValue;
    }

    static boolean isCommandArgumentPresent(final String argToNameForFind, final String[] args) {
        for (String argToCheck : args) {
            if (argToCheck.equals(argToNameForFind)) {
                return true;
            }
        }
        return false;
    }
}