import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Run with app_process against an APK or a standalone matcher plus the APK's Kotlin runtime. */
public final class ExternalMatcherProbe {
    private static Method find(Class<?> type, String prefix, int count) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().startsWith(prefix) && !method.getName().contains("$default") && method.getParameterTypes().length == count) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("Missing matcher method: " + prefix);
    }
    public static void main(String[] args) throws Exception {
        ClassLoader loader = "classpath".equals(args[0]) ? ExternalMatcherProbe.class.getClassLoader()
            : new dalvik.system.PathClassLoader(args[0], ExternalMatcherProbe.class.getClassLoader());
        Class<?> matcher = Class.forName("com.nuvio.tv.data.iptv.ExternalAddonTitleMatcher", true, loader);
        Object instance = matcher.getField("INSTANCE").get(null);
        String arabic = "\u0627\u0644\u0645\u062f\u064a\u0646\u0629 \u0627\u0644\u0628\u0639\u064a\u062f\u0629";
        String mixed = "Uzak \u015eehir " + arabic;
        Method variants = find(matcher, "titleVariants", 1);
        variants.invoke(instance, mixed);
        System.out.println("PASS Android bilingual title extraction");
        Class<?> candidate = Class.forName(matcher.getName() + "$Candidate", true, loader);
        java.lang.reflect.Constructor<?> ctor = candidate.getDeclaredConstructor(String.class, String.class, Integer.class, String.class, String.class);
        Object translated = ctor.newInstance("xt_s_probe_1", mixed, Integer.valueOf(2024), null, null);
        Method choose = find(matcher, "choose", 5);
        Object matched = choose.invoke(instance, Arrays.asList(arabic), Integer.valueOf(2024), Arrays.asList(translated), null, null);
        if (!"Matched".equals(matched.getClass().getSimpleName())) throw new AssertionError("Bilingual work did not match");
        System.out.println("PASS Android translated edition title match");
        List<Object> editions = new ArrayList<>();
        for (int n = 0; n < 17; n++) editions.add(ctor.newInstance("xt_s_probe_" + n, "A Different World", Integer.valueOf(1987), null, null));
        Object ambiguous = choose.invoke(instance, Arrays.asList("A Different World"), Integer.valueOf(1987), editions, null, null);
        if (!"Ambiguous".equals(ambiguous.getClass().getSimpleName())) throw new AssertionError("Duplicate titles were accepted");
        System.out.println("PASS Android ambiguous-edition rejection");
    }
}
