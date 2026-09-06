package org.techhouse.simplejs.internal.interpreter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.techhouse.simplejs.elements.SourcePosition;

/**
 * The interpreter's own call stack, kept so a thrown error can name where it came from.
 *
 * <p>
 * Only one thread runs interpreter code at a time - a coroutine parks its parent before running - which is the
 * same invariant the depth counter beside it already relies on, so nothing here is synchronized. A coroutine
 * still needs its own {@link Segment}: its body runs interleaved with its consumer's, so sharing one frame
 * list would leave a suspended generator's frame visible in the trace of whatever resumed it.
 */
public final class CallStack {
    public static final String TOP_LEVEL_MODULE = "main";
    public static final String ANONYMOUS = "<anonymous>";
    public static final String NATIVE_MODULE = "native";

    public static final class Segment {
        private final Deque<CallFrame> frames = new ArrayDeque<>();
        private String function;
        private String module = TOP_LEVEL_MODULE;
        private int line;
        private int column;
    }

    private Segment current = new Segment();

    public Segment segmentFor(String functionName, String moduleName) {
        final var segment = new Segment();
        segment.function = functionName == null || functionName.isEmpty() ? ANONYMOUS : functionName;
        if (moduleName != null) {
            segment.module = moduleName;
        }
        return segment;
    }

    public Segment swap(Segment segment) {
        final var previous = current;
        current = segment;
        return previous;
    }

    public void push(String functionName, String moduleName) {
        current.frames.push(new CallFrame(current.function, current.module, current.line, current.column));
        current.function = functionName == null || functionName.isEmpty() ? ANONYMOUS : functionName;
        if (moduleName != null) {
            current.module = moduleName;
        }
        current.line = 0;
        current.column = 0;
    }

    public void pop() {
        final var frame = current.frames.poll();
        if (frame == null) {
            return;
        }
        current.function = frame.getCallerFunction();
        current.module = frame.getCallerModule();
        current.line = frame.getCallerLine();
        current.column = frame.getCallerColumn();
    }

    public void setPosition(SourcePosition position) {
        if (position != null) {
            current.line = position.getLine();
            current.column = position.getColumn();
        }
    }

    public String currentModule() {
        return current.module;
    }

    public String enterModule(String moduleName) {
        final var previous = current.module;
        if (moduleName != null) {
            current.module = moduleName;
        }
        return previous;
    }

    public void exitModule(String saved) {
        current.module = saved;
    }

    public List<String> capture(int maxFrames) {
        final var rendered = new ArrayList<String>();
        rendered.add(render(current.function, current.module, current.line, current.column));
        for (final var frame : current.frames) {
            rendered.add(render(frame.getCallerFunction(), frame.getCallerModule(), frame.getCallerLine(),
                    frame.getCallerColumn()));
        }
        if (maxFrames >= 0 && rendered.size() > maxFrames) {
            final var dropped = rendered.size() - maxFrames;
            final var truncated = new ArrayList<>(rendered.subList(0, maxFrames));
            truncated.add("... " + dropped + " more frame" + (dropped == 1 ? "" : "s"));
            return List.copyOf(truncated);
        }
        return List.copyOf(rendered);
    }

    private static String render(String functionName, String moduleName, int line, int column) {
        final var location = new StringBuilder(moduleName == null ? TOP_LEVEL_MODULE : moduleName);
        if (line > 0) {
            location.append(':').append(line).append(':').append(column);
        }
        if (functionName == null) {
            return location.toString();
        }
        return functionName + " (" + location + ")";
    }
}
