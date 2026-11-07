import java.util.*;

/** Read-only app_process probe against the actual APK; does not touch playback or account data. */
public final class CumulativeEpisodeProbe {
    private static List<Integer> range(int from, int to) {
        List<Integer> result = new ArrayList<>();
        for (int n = from; n <= to; n++) result.add(n);
        return result;
    }
    public static void main(String[] args) throws Exception {
        ClassLoader loader = new dalvik.system.PathClassLoader(args[0], CumulativeEpisodeProbe.class.getClassLoader());
        Class<?> type = Class.forName("com.nuvio.tv.data.iptv.ExternalEpisodeNumbering", true, loader);
        Object instance = type.getField("INSTANCE").get(null);
        java.lang.reflect.Method offset = type.getDeclaredMethod("offset", Map.class, Map.class,
            int.class, int.class, Set.class, boolean.class);
        Map<Integer, Set<Integer>> canonical = new HashMap<>();
        canonical.put(1, new HashSet<>(range(1, 28)));
        canonical.put(2, new HashSet<>(range(1, 35)));
        canonical.put(3, new HashSet<>(range(1, 35)));
        Map<Integer, List<Integer>> nativeSeasons = new HashMap<>();
        nativeSeasons.put(1, range(1, 28));
        nativeSeasons.put(2, range(29, 63));
        nativeSeasons.put(3, range(64, 71));
        Object second = offset.invoke(instance, canonical, nativeSeasons, 2, 2, canonical.get(2), false);
        if (!Integer.valueOf(28).equals(second)) throw new AssertionError("Incorrect second-season offset");
        System.out.println("PASS Android S2E8 -> native S2E36 and S2E35 -> native S2E63");
        Object third = offset.invoke(instance, canonical, nativeSeasons, 3, 3, new HashSet<>(range(1, 8)), false);
        if (!Integer.valueOf(63).equals(third)) throw new AssertionError("Incorrect third-season offset");
        System.out.println("PASS Android aired S3E8 -> native S3E71");
        nativeSeasons.put(1, range(1, 140));
        if (offset.invoke(instance, canonical, nativeSeasons, 2, 2, canonical.get(2), false) != null)
            throw new AssertionError("Recut edition accepted");
        System.out.println("PASS Android recut edition rejected");
    }
}
