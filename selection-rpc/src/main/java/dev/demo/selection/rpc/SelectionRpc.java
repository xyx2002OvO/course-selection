package dev.demo.selection.rpc;

public interface SelectionRpc {
    SubmitResult submit(SubmitCommand command);
    StatusView status(StatusQuery query);
}
