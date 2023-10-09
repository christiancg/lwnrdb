package org.techhouse.ejson.internal;

import java.io.*;
import java.math.BigDecimal;

/**
 * This class holds a number value that is lazily converted to a specific number type
 *
 * @author Inderjeet Singh
 */
public final class LazilyParsedNumber extends Number {
  private final String value;

  /** @param value must not be null */
  public LazilyParsedNumber(String value) {
    this.value = value;
  }

  @Override
  public int intValue() {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      try {
        return (int) Long.parseLong(value);
      } catch (NumberFormatException nfe) {
        return new BigDecimal(value).intValue();
      }
    }
  }

  @Override
  public long longValue() {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return new BigDecimal(value).longValue();
    }
  }

  @Override
  public float floatValue() {
    return Float.parseFloat(value);
  }

  @Override
  public double doubleValue() {
    return Double.parseDouble(value);
  }

  @Override
  public String toString() {
    return value;
  }

  /**
   * If somebody is unlucky enough to have to serialize one of these, serialize
   * it as a BigDecimal so that they won't need EJson on the other side to
   * deserialize it.
   */
  @Serial
  private Object writeReplace() throws ObjectStreamException {
    return new BigDecimal(value);
  }

  @Serial
  private void readObject(ObjectInputStream in) throws IOException {
    // Don't permit directly deserializing this class; writeReplace() should have written a replacement
    throw new InvalidObjectException("Deserialization is unsupported");
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj instanceof LazilyParsedNumber other) {
      return value.equals(other.value);
    }
    return false;
  }
}
