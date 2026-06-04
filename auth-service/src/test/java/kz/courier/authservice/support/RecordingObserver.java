package kz.courier.authservice.support;

import io.grpc.stub.StreamObserver;

public final class RecordingObserver<T> implements StreamObserver<T> {

    private T value;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(T value) {
        this.value = value;
    }

    @Override
    public void onError(Throwable throwable) {
        this.error = throwable;
    }

    @Override
    public void onCompleted() {
        this.completed = true;
    }

    public T value() {
        return value;
    }

    public Throwable error() {
        return error;
    }

    public boolean completed() {
        return completed;
    }
}
