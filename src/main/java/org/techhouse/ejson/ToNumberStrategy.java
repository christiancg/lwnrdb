package org.techhouse.ejson;

import org.techhouse.ejson.stream.JsonReader;

import java.io.IOException;

/**
 * A strategy that is used to control how numbers should be deserialized for {@link Object} and {@link Number}
 * when a concrete type of the deserialized number is unknown in advance. By default, EJson uses the following
 * deserialization strategies:
 *
 * <ul>
 * <li>{@link Double} values are returned for JSON numbers if the deserialization type is declared as
 * {@code Object}, see {@link ToNumberPolicy#DOUBLE};</li>
 * <li>Lazily parsed number values are returned if the deserialization type is declared as {@code Number},
 * see {@link ToNumberPolicy#LAZILY_PARSED_NUMBER}.</li>
 * </ul>
 *
 * <p>For historical reasons, EJson does not support deserialization of arbitrary-length numbers for
 * {@code Object} and {@code Number} by default, potentially causing precision loss. However,
 * <a href="https://tools.ietf.org/html/rfc8259#section-6">RFC 8259</a> permits this:
 *
 * <pre>
 *   This specification allows implementations to set limits on the range
 *   and precision of numbers accepted.  Since software that implements
 *   IEEE 754 binary64 (double precision) numbers [IEEE754] is generally
 *   available and widely used, good interoperability can be achieved by
 *   implementations that expect no more precision or range than these
 *   provide, in the sense that implementations will approximate JSON
 *   numbers within the expected precision.  A JSON number such as 1E400
 *   or 3.141592653589793238462643383279 may indicate potential
 *   interoperability problems, since it suggests that the software that
 *   created it expects receiving software to have greater capabilities
 *   for numeric magnitude and precision than is widely available.
 * </pre>
 *
 * <p>To overcome the precision loss, use for example {@link ToNumberPolicy#LONG_OR_DOUBLE} or
 * {@link ToNumberPolicy#BIG_DECIMAL}.</p>
 *
 * @see ToNumberPolicy
 * @see EJsonBuilder#setObjectToNumberStrategy(ToNumberStrategy)
 * @see EJsonBuilder#setNumberToNumberStrategy(ToNumberStrategy)
 * @since 2.8.9
 */
public interface ToNumberStrategy {

  /**
   * Reads a number from the given JSON reader. A strategy is supposed to read a single value from the
   * reader, and the read value is guaranteed never to be {@code null}.
   *
   * @param in JSON reader to read a number from
   * @return number read from the JSON reader.
   */
  Number readNumber(JsonReader in) throws IOException;
}
