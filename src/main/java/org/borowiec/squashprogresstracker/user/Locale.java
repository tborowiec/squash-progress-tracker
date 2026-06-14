package org.borowiec.squashprogresstracker.user;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

public enum Locale {
    EN("en"),
    PL("pl");

    private final String tag;

    Locale(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public static Locale fromTag(String tag) {
        for (var locale : values()) {
            if (locale.tag.equalsIgnoreCase(tag)) {
                return locale;
            }
        }
        return EN;
    }

    @Converter(autoApply = true)
    public static class JpaConverter implements AttributeConverter<Locale, String> {

        @Override
        public String convertToDatabaseColumn(Locale locale) {
            return locale == null ? EN.tag : locale.tag();
        }

        @Override
        public Locale convertToEntityAttribute(String dbData) {
            return fromTag(dbData);
        }
    }
}
