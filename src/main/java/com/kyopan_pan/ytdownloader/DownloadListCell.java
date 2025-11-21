package com.kyopan_pan.ytdownloader;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.io.File;
import java.util.function.Consumer;

public class DownloadListCell extends ListCell<File> {

    private final Consumer<File> onDelete;
    private final Label nameLabel = new Label();
    private final Button deleteBtn = new Button();
    private final Region spacer = new Region();
    private final HBox container = new HBox(10, nameLabel, spacer, deleteBtn);

    public DownloadListCell(Consumer<File> onDelete) {
        this.onDelete = onDelete;
        nameLabel.getStyleClass().add("file-name");
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setEllipsisString("...");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        nameLabel.setMinWidth(0);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        container.setAlignment(Pos.CENTER_LEFT);
        container.getStyleClass().add("file-row");
        container.setMaxWidth(Double.MAX_VALUE);
        setMaxWidth(Double.MAX_VALUE);

        listViewProperty().addListener((obs, oldList, newList) -> {
            prefWidthProperty().unbind();
            if (newList != null) {
                prefWidthProperty().bind(newList.widthProperty().subtract(16));
            }
        });

        deleteBtn.setGraphic(IconFactory.createDeleteIcon());
        deleteBtn.getStyleClass().add("delete-btn");
        deleteBtn.setFocusTraversable(false);
        deleteBtn.setOnAction(event -> {
            File target = getItem();
            if (target != null) {
                onDelete.accept(target);
            }
            event.consume();
        });
    }

    @Override
    protected void updateItem(File item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setText(null);
            setGraphic(null);
        } else {
            nameLabel.setText(item.getName());
            setText(null);
            setGraphic(container);
        }
    }
}
