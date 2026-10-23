package pl.pollub.pari.media.service;

/** What an uploaded image is for; decides the folder inside the storage directory. */
public enum MediaCategory {
    SPOT_PHOTO("spots"),
    AVATAR("avatars");

    private final String folder;

    MediaCategory(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
