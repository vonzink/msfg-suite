package com.msfg.los.notes.web.dto;

import java.util.List;

/** Notes list payload: total count + the newest-first note rows. */
public record NoteListResponse(int count, List<NoteResponse> notes) {

    public static NoteListResponse of(List<NoteResponse> rows) {
        return new NoteListResponse(rows.size(), rows);
    }
}
