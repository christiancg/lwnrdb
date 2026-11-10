package io.github.christiancg.lwnrdb.client;

@FunctionalInterface
public interface TransactionBody<R> {
    R run(Transaction transaction);
}
