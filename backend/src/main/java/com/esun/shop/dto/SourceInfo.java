package com.esun.shop.dto;

public class SourceInfo {
    private String sourceType;
    private String sourceId;
    private String title;
    private double similarity;

    public SourceInfo() {
    }

    public SourceInfo(String sourceType, String sourceId, String title, double similarity) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.title = title;
        this.similarity = similarity;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(double similarity) {
        this.similarity = similarity;
    }
}
