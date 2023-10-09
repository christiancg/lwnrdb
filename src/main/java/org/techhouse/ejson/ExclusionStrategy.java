package org.techhouse.ejson;

/**
 * A strategy (or policy) definition that is used to decide whether or not a field or
 * class should be serialized or deserialized as part of the JSON output/input.
 *
 * <p>The following are a few examples that shows how you can use this exclusion mechanism.
 *
 * <p><strong>Exclude fields and objects based on a particular class type:</strong>
 * <pre class="code">
 * private static class SpecificClassExclusionStrategy implements ExclusionStrategy {
 *   private final Class&lt;?&gt; excludedThisClass;
 *   public SpecificClassExclusionStrategy(Class&lt;?&gt; excludedThisClass) {
 *     this.excludedThisClass = excludedThisClass;
 *   }
 *   public boolean shouldSkipClass(Class&lt;?&gt; clazz) {
 *     return excludedThisClass.equals(clazz);
 *   }
 *   public boolean shouldSkipField(FieldAttributes f) {
 *     return excludedThisClass.equals(f.getDeclaredClass());
 *   }
 * }
 * </pre>
 *
 * <p><strong>Excludes fields and objects based on a particular annotation:</strong>
 * <pre class="code">
 * public &#64;interface FooAnnotation {
 *   // some implementation here
 * }
 * // Excludes any field (or class) that is tagged with an "&#64;FooAnnotation"
 * private static class FooAnnotationExclusionStrategy implements ExclusionStrategy {
 *   public boolean shouldSkipClass(Class&lt;?&gt; clazz) {
 *     return clazz.getAnnotation(FooAnnotation.class) != null;
 *   }
 *   public boolean shouldSkipField(FieldAttributes f) {
 *     return f.getAnnotation(FooAnnotation.class) != null;
 *   }
 * }
 * </pre>
 *
 * <p>Now if you want to configure {@code EJson} to use a user defined exclusion strategy, then
 * the {@code EJsonBuilder} is required. The following is an example of how you can use the
 * {@code EJsonBuilder} to configure EJson to use one of the above samples:
 * <pre class="code">
 * ExclusionStrategy excludeStrings = new UserDefinedExclusionStrategy(String.class);
 * EJson eJson = new EJsonBuilder()
 *     .setExclusionStrategies(excludeStrings)
 *     .create();
 * </pre>
 *
 * <p>For certain model classes, you may only want to serialize a field, but exclude it for
 * deserialization. To do that, you can write an {@code ExclusionStrategy} as per normal;
 * however, you would register it with the
 * {@link EJsonBuilder#addDeserializationExclusionStrategy(ExclusionStrategy)} method.
 * For example:
 * <pre class="code">
 * ExclusionStrategy excludeStrings = new UserDefinedExclusionStrategy(String.class);
 * EJson eJson = new EJsonBuilder()
 *     .addDeserializationExclusionStrategy(excludeStrings)
 *     .create();
 * </pre>
 *
 * @author Inderjeet Singh
 * @author Joel Leitch
 *
 * @see EJsonBuilder#setExclusionStrategies(ExclusionStrategy...)
 * @see EJsonBuilder#addDeserializationExclusionStrategy(ExclusionStrategy)
 * @see EJsonBuilder#addSerializationExclusionStrategy(ExclusionStrategy)
 *
 * @since 1.4
 */
public interface ExclusionStrategy {

  /**
   * Decides if a field should be skipped during serialization or deserialization.
   *
   * @param f the field object that is under test
   * @return true if the field should be ignored; otherwise false
   */
  boolean shouldSkipField(FieldAttributes f);

  /**
   * Decides if a class should be serialized or deserialized
   *
   * @param clazz the class object that is under test
   * @return true if the class should be ignored; otherwise false
   */
  boolean shouldSkipClass(Class<?> clazz);
}
