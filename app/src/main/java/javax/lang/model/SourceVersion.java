package javax.lang.model;
/** Stub mínimo p/ ECJ rodar no Android (sem processamento de anotações). */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4,
    RELEASE_5, RELEASE_6, RELEASE_7, RELEASE_8, RELEASE_9,
    RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13, RELEASE_14,
    RELEASE_15, RELEASE_16, RELEASE_17, RELEASE_18, RELEASE_19,
    RELEASE_20, RELEASE_21, RELEASE_22, RELEASE_23;
    public static SourceVersion latest() { return RELEASE_8; }
    public static SourceVersion latestSupported() { return RELEASE_8; }
    public static boolean isName(CharSequence name) { return true; }
    public static boolean isName(CharSequence name, SourceVersion version) { return true; }
}
