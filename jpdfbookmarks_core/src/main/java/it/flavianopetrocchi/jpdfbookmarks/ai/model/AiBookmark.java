/*
 * AiBookmark.java
 *
 * Copyright (c) 2010 Flaviano Petrocchi <flavianopetrocchi at gmail.com>.
 * All rights reserved.
 *
 * This file is part of JPdfBookmarks.
 *
 * JPdfBookmarks is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPdfBookmarks is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JPdfBookmarks.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.flavianopetrocchi.jpdfbookmarks.ai.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import it.flavianopetrocchi.jpdfbookmarks.ai.service.AiModelConverter;
import it.flavianopetrocchi.jpdfbookmarks.bookmark.Bookmark;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * DTO for a single bookmark node produced by an LLM (structured JSON).
 * Hierarchy is represented as a tree: each node may contain nested {@link #children}.
 * <p>
 * This type is intentionally <strong>not</strong> a subclass of {@link Bookmark}: that class extends
 * {@link javax.swing.tree.DefaultMutableTreeNode}, which is a poor Jackson deserialization target and mixes
 * Swing tree wiring with PDF bookmark state. Use {@link #toBookmark()} to obtain nodes for the existing UI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiBookmark {

    @JsonProperty("title")
    @JsonAlias({"name", "label", "text"})
    private String title;

    /**
     * 1-based page index as used by JPdfBookmarks when the bookmark targets a page.
     */
    @JsonProperty("page_number")
    @JsonAlias({"page", "pageNumber", "pg"})
    private Integer pageNumber;

    /**
     * Depth in the tree (0 = root level). Optional hint for models that emit a flat level field.
     */
    @JsonProperty("level")
    @JsonAlias({"depth", "indent_level"})
    private Integer level;

    /**
     * Child bookmarks, in order from first to last sibling.
     */
    @JsonProperty("children")
    @JsonAlias({"items", "nodes", "bookmarks"})
    private List<AiBookmark> children;

    public AiBookmark() {
        this.children = new ArrayList<>();
    }

    public AiBookmark(String title, Integer pageNumber) {
        this();
        this.title = title;
        this.pageNumber = pageNumber;
    }

    public AiBookmark(String title, Integer pageNumber, List<AiBookmark> children) {
        this.title = title;
        this.pageNumber = pageNumber;
        setChildren(children);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public List<AiBookmark> getChildren() {
        return children;
    }

    public void setChildren(List<AiBookmark> children) {
        this.children = children != null ? new ArrayList<>(children) : new ArrayList<>();
    }

    /**
     * @return an unmodifiable view of {@link #children}
     */
    public List<AiBookmark> getChildrenView() {
        return Collections.unmodifiableList(children);
    }

    public void addChild(AiBookmark child) {
        Objects.requireNonNull(child, "child");
        children.add(child);
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    /**
     * Builds a {@link Bookmark} subtree compatible with {@code JTree} / JPdfBookmarks, using defaults for
     * fields the LLM did not supply (destination type, colour, etc.).
     */
    public Bookmark toBookmark() {
        return AiModelConverter.toAppBookmark(this);
    }
}
