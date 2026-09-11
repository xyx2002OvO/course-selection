package dev.demo.selection.infrastructure;

import org.springframework.stereotype.Component;

@Component
public class CatalogMode {
    private volatile boolean frozen;

    public boolean frozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }
}
