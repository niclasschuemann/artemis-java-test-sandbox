package de.tum.in.test.api.structural;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;

/**
 * @author Stephan Krusche (krusche@in.tum.de)
 * @version 5.0 (11.11.2020)
 * <br><br>
 * This test evaluates the hierarchy of the class, i.e. if the class is abstract or an interface or an enum and also if the class extends another superclass and if
 * it implements the interfaces and annotations, based on its definition in the structure oracle (test.json).
 */
public abstract class ClassTest extends StructuralTest {

    /**
     * This method collects the classes in the structure oracle file for which at least one class property is specified.
     * These classes are then transformed into JUnit 5 dynamic tests.
     * @return A dynamic test container containing the test for each class which is then executed by JUnit.
     * @throws URISyntaxException an exception if the URI of the class name cannot be generated (which seems to be unlikely)
     */
    protected DynamicContainer generateTestsForAllClasses() throws URISyntaxException {
        List<DynamicNode> tests = new ArrayList<>();

        if (structureOracleJSON == null) {
            fail("The LocalClassTest test can only run if the structural oracle (test.json) is present. If you do not provide it, delete LocalClassTest.java!");
        }

        for (int i = 0; i < structureOracleJSON.length(); i++) {
            JSONObject expectedClassJSON = structureOracleJSON.getJSONObject(i);
            JSONObject expectedClassPropertiesJSON = expectedClassJSON.getJSONObject(JSON_PROPERTY_CLASS);

            // Only test the classes that have additional properties (except name and package) defined in the structure oracle.
            if (expectedClassPropertiesJSON.has(JSON_PROPERTY_NAME) && expectedClassPropertiesJSON.has(JSON_PROPERTY_PACKAGE) && hasAdditionalProperties(expectedClassPropertiesJSON)) {
                String expectedClassName = expectedClassPropertiesJSON.getString(JSON_PROPERTY_NAME);
                String expectedPackageName = expectedClassPropertiesJSON.getString(JSON_PROPERTY_PACKAGE);
                ExpectedClassStructure expectedClassStructure = new ExpectedClassStructure(expectedClassName, expectedPackageName, expectedClassJSON);
                tests.add(dynamicTest("testClass[" + expectedClassName + "]", () -> testClass(expectedClassStructure)));
            }
        }
        if (tests.isEmpty()) {
            fail("No tests for classes available in the structural oracle (test.json). Either provide attributes information or delete ClassTest.java!");
        }
        // Using a custom URI here to workaround surefire rendering the JUnit XML without the correct test names.
        return dynamicContainer(getClass().getName(), new URI(getClass().getName()), tests.stream());
    }

    protected static boolean hasAdditionalProperties(JSONObject jsonObject) {
        List<String> keys = new ArrayList<>(jsonObject.keySet());
        keys.remove(JSON_PROPERTY_NAME);
        keys.remove(JSON_PROPERTY_PACKAGE);
        return !keys.isEmpty();
    }

    /**
     * This method gets passed the expected class structure generated by the method generateTestsForAllClasses(), checks if the class is found
     * at all in the assignment and then proceeds to check its properties.
     * @param expectedClassStructure: The class structure that we expect to find and test against.
     */
    protected void testClass(ExpectedClassStructure expectedClassStructure) {
        String expectedClassName = expectedClassStructure.getExpectedClassName();
        Class<?> observedClass = findClassForTestType(expectedClassStructure, "class");
        if (observedClass == null) {
            fail(THE_CLASS + expectedClassName + " was not found for class test");
            return;
        }

        JSONObject expectedClassPropertiesJSON = expectedClassStructure.getPropertyAsJsonObject(JSON_PROPERTY_CLASS);

        checkBasicClassProperties(expectedClassName, observedClass, expectedClassPropertiesJSON);
        checkSuperclass(expectedClassName, observedClass, expectedClassPropertiesJSON);
        checkInterfaces(expectedClassName, observedClass, expectedClassPropertiesJSON);
        checkAnnotations(expectedClassName, observedClass, expectedClassPropertiesJSON);
    }

    private void checkBasicClassProperties(String expectedClassName, Class<?> observedClass, JSONObject expectedClassPropertiesJSON) {
        if (expectedClassPropertiesJSON.has("isAbstract") && !Modifier.isAbstract(observedClass.getModifiers())) {
            fail(THE_CLASS + "'" + expectedClassName + "' is not abstract as it is expected.");
        }

        if (expectedClassPropertiesJSON.has("isEnum") && !observedClass.isEnum()) {
            fail(THE_TYPE + "'" + expectedClassName + "' is not an enum as it is expected.");
        }

        if (expectedClassPropertiesJSON.has("isInterface") && !Modifier.isInterface(observedClass.getModifiers())) {
            fail(THE_TYPE + "'" + expectedClassName + "' is not an interface as it is expected.");
        }

        if(expectedClassPropertiesJSON.has("isEnum") && !observedClass.isEnum()) {
            fail(THE_TYPE + "'" + expectedClassName + "' is not an enum as it is expected.");
        }
    }

    private void checkSuperclass(String expectedClassName, Class<?> observedClass, JSONObject expectedClassPropertiesJSON) {
        // Filter out the enums, since there is a separate test for them
        if(expectedClassPropertiesJSON.has(JSON_PROPERTY_SUPERCLASS) && !expectedClassPropertiesJSON.getString(JSON_PROPERTY_SUPERCLASS).equals("Enum")) {
            String expectedSuperClassName = expectedClassPropertiesJSON.getString(JSON_PROPERTY_SUPERCLASS);
            String actualSuperClassName = observedClass.getSuperclass().getSimpleName();

            String failMessage = THE_CLASS + "'" + expectedClassName + "' is not a subclass of the class '"
                    + expectedSuperClassName + "' as expected. Implement the class inheritance properly.";
            if (!expectedSuperClassName.equals(actualSuperClassName)) {
                fail(failMessage);
            }
        }
    }

    private void checkInterfaces(String expectedClassName, Class<?> observedClass, JSONObject expectedClassPropertiesJSON) {
        if(expectedClassPropertiesJSON.has(JSON_PROPERTY_INTERFACES)) {
            JSONArray expectedInterfaces = expectedClassPropertiesJSON.getJSONArray(JSON_PROPERTY_INTERFACES);
            Class<?>[] observedInterfaces = observedClass.getInterfaces();

            for (int i = 0; i < expectedInterfaces.length(); i++) {
                String expectedInterface = expectedInterfaces.getString(i);
                boolean implementsInterface = false;

                for (Class<?> observedInterface : observedInterfaces) {
                    //TODO: this does not work with the current implementation of the test oracle generator (which does not print the simple but the full qualified name including the package)
                    if(expectedInterface.equals(observedInterface.getSimpleName())) {
                        implementsInterface = true;
                        break;
                    }
                }

                if (!implementsInterface) {
                    fail(THE_CLASS + "'" + expectedClassName + "' does not implement the interface '" + expectedInterface + "' as expected."
                            + " Implement the interface and its methods.");
                }
            }
        }
    }

    private void checkAnnotations(String expectedClassName, Class<?> observedClass, JSONObject expectedClassPropertiesJSON) {
        if(expectedClassPropertiesJSON.has(JSON_PROPERTY_ANNOTATIONS)) {
            JSONArray expectedAnnotations = expectedClassPropertiesJSON.getJSONArray(JSON_PROPERTY_ANNOTATIONS);
            Annotation[] observedAnnotations = observedClass.getAnnotations();

            boolean annotationsAreRight = checkAnnotations(observedAnnotations, expectedAnnotations);
            if (!annotationsAreRight) {
                fail("The annotation(s) of the class '" + expectedClassName + "' are not implemented as expected.");
            }
        }
    }
}
