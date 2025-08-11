package io.jafar.parser.internal_api;

import java.lang.invoke.MethodHandles;

/**
 * Utility class for defining classes with automatic runtime detection of the best available method.
 * <p>
 * This class automatically chooses between defineHiddenClass (Java 11+) and 
 * ClassLoader.defineClass (Java 8+) based on what's available at runtime.
 * </p>
 */
public final class ClassDefinitionHelper {
    
    private static final boolean SUPPORTS_HIDDEN_CLASS = isHiddenClassSupported();
    
    private ClassDefinitionHelper() {
        // Utility class, no instantiation
    }
    
    /**
     * Result of class definition containing both the class and the lookup object.
     * <p>
     * This is needed to maintain proper access privileges when using defineHiddenClass.
     * </p>
     */
    public static final class ClassDefinitionResult {
        private final Class<?> generatedClass;
        private final MethodHandles.Lookup lookup;
        
        public ClassDefinitionResult(Class<?> generatedClass, MethodHandles.Lookup lookup) {
            this.generatedClass = generatedClass;
            this.lookup = lookup;
        }
        
        public Class<?> getGeneratedClass() {
            return generatedClass;
        }
        
        public MethodHandles.Lookup getLookup() {
            return lookup;
        }
    }
    
    /**
     * Detects whether the current JVM supports defineHiddenClass.
     * 
     * @return true if defineHiddenClass is available, false otherwise
     */
    private static boolean isHiddenClassSupported() {
        try {
            // Try to access the ClassOption enum - available in Java 11+
            Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
            
            // Also check if the defineHiddenClass method exists
            MethodHandles.Lookup lkp = MethodHandles.lookup();
            try {
                lkp.getClass().getMethod("defineHiddenClass", byte[].class, boolean.class, Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption"));
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Defines a class with automatic method selection based on runtime capabilities.
     * 
     * @param name the fully qualified name of the class
     * @param classData the class file bytes
     * @param initialize whether to initialize the class
     * @return a result containing both the defined Class object and the lookup object
     * @throws Exception if class definition fails
     */
    public static ClassDefinitionResult defineClass(String name, byte[] classData, boolean initialize) throws Exception {
        if (SUPPORTS_HIDDEN_CLASS) {
            try {
                return defineHiddenClass(classData, initialize);
            } catch (Exception e) {
                // If defineHiddenClass fails, fall back to legacy method
                return defineClassLegacy(name, classData, initialize);
            }
        } else {
            return defineClassLegacy(name, classData, initialize);
        }
    }
    
    /**
     * Defines a class using defineHiddenClass (Java 11+).
     * 
     * @param classData the class file bytes
     * @param initialize whether to initialize the class
     * @return a result containing both the defined Class object and the lookup object
     * @throws Exception if class definition fails
     */
    private static ClassDefinitionResult defineHiddenClass(byte[] classData, boolean initialize) throws Exception {
        MethodHandles.Lookup lkp = MethodHandles.lookup();
        // Use reflection to access defineHiddenClass and ClassOption.NESTMATE for Java 8 compatibility
        Class<?> classOptionClass = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
        Object nestmateOption = Enum.valueOf((Class<Enum>) classOptionClass, "NESTMATE");
        
        // Use reflection to call defineHiddenClass
        java.lang.reflect.Method defineHiddenClassMethod = lkp.getClass().getMethod("defineHiddenClass", byte[].class, boolean.class, classOptionClass);
        Class<?> generatedClass = (Class<?>) defineHiddenClassMethod.invoke(lkp, classData, initialize, nestmateOption);
        
        return new ClassDefinitionResult(generatedClass, lkp);
    }
    
    /**
     * Defines a class using ClassLoader.defineClass (Java 8+).
     * 
     * @param name the fully qualified name of the class
     * @param classData the class file bytes
     * @param initialize whether to initialize the class
     * @return a result containing both the defined Class object and a new lookup object
     * @throws Exception if class definition fails
     */
    private static ClassDefinitionResult defineClassLegacy(String name, byte[] classData, boolean initialize) throws Exception {
        ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(String className) throws ClassNotFoundException {
                if (className.equals(name)) {
                    return defineClass(name, classData, 0, classData.length);
                }
                return super.findClass(className);
            }
        };
        Class<?> generatedClass = loader.loadClass(name);
        return new ClassDefinitionResult(generatedClass, MethodHandles.lookup());
    }
} 