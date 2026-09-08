package org.techhouse.simplejs.exceptions;

public class UnterminatedCommentException extends RuntimeException {
    public UnterminatedCommentException(int position) {
        super("Unterminated comment starting at position: " + position);
    }
}
