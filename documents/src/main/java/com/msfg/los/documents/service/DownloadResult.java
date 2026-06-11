package com.msfg.los.documents.service;

import com.msfg.los.documents.domain.Document;

public record DownloadResult(Document doc, byte[] bytes) {}
