package com.doctorapp;

public class Topic {
    public String title;
    public String sourceUrl;
    public String speciality;
    public String imageUrl;
    public String contentPreview;

    public Topic(String title, String sourceUrl, String speciality, String imageUrl, String contentPreview) {
        this.title = title;
        this.sourceUrl = sourceUrl;
        this.speciality = speciality;
        this.imageUrl = imageUrl;
        this.contentPreview = contentPreview;
    }
}