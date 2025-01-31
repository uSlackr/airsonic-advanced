/*
 This file is part of Airsonic.

 Airsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Airsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Airsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2016 (C) Airsonic Authors
 Based upon Subsonic, Copyright 2009 (C) Sindre Mehus
 */
package org.airsonic.player.dao;

import com.google.common.collect.ImmutableMap;
import org.airsonic.player.domain.Genre;
import org.airsonic.player.domain.MediaFile;
import org.airsonic.player.domain.MusicFolder;
import org.airsonic.player.domain.RandomSearchCriteria;
import org.airsonic.player.util.Util;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Provides database services for media files.
 *
 * @author Sindre Mehus
 */
@Repository
public class MediaFileDao extends AbstractDao {
    private static final Logger LOG = LoggerFactory.getLogger(MediaFileDao.class);
    private static final String INSERT_COLUMNS = "path, folder, type, format, title, album, artist, album_artist, disc_number, " +
                                                "track_number, year, genre, bit_rate, variable_bit_rate, duration, file_size, width, height, cover_art_path, " +
                                                "parent_path, play_count, last_played, comment, created, changed, last_scanned, children_last_updated, present, " +
                                                "version, mb_release_id, mb_recording_id";

    private static final String QUERY_COLUMNS = "id, " + INSERT_COLUMNS;
    private static final String GENRE_COLUMNS = "name, song_count, album_count";

    public static final int VERSION = 4;

    private final MediaFileMapper rowMapper = new MediaFileMapper();
    private final MusicFileInfoMapper musicFileInfoRowMapper = new MusicFileInfoMapper();
    private final GenreMapper genreRowMapper = new GenreMapper();

    /**
     * Returns the media file for the given path.
     *
     * @param path The path.
     * @return The media file or null.
     */
    public MediaFile getMediaFile(String path) {
        return queryOne("select " + QUERY_COLUMNS + " from media_file where path=?", rowMapper, path);
    }

    /**
     * Returns the media file for the given ID.
     *
     * @param id The ID.
     * @return The media file or null.
     */
    public MediaFile getMediaFile(int id) {
        return queryOne("select " + QUERY_COLUMNS + " from media_file where id=?", rowMapper, id);
    }

    /**
     * Returns the media file that are direct children of the given path.
     *
     * @param path The path.
     * @return The list of children.
     */
    public List<MediaFile> getChildrenOf(String path) {
        return query("select " + QUERY_COLUMNS + " from media_file where parent_path=? and present", rowMapper, path);
    }

    public List<MediaFile> getFilesInPlaylist(int playlistId) {
        return query("select " + prefix(QUERY_COLUMNS, "media_file") + " from playlist_file, media_file where " +
                     "media_file.id = playlist_file.media_file_id and " +
                     "playlist_file.playlist_id = ? " +
                     "order by playlist_file.id", rowMapper, playlistId);
    }

    public List<MediaFile> getSongsForAlbum(String artist, String album) {
        return query("select " + QUERY_COLUMNS + " from media_file where album_artist=? and album=? and present " +
                     "and type in (?,?,?) order by disc_number, track_number", rowMapper,
                     artist, album, MediaFile.MediaType.MUSIC.name(), MediaFile.MediaType.AUDIOBOOK.name(), MediaFile.MediaType.PODCAST.name());
    }

    public List<MediaFile> getVideos(final int count, final int offset, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.VIDEO.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("count", count);
        args.put("offset", offset);
        return namedQuery("select " + QUERY_COLUMNS
                          + " from media_file where type = :type and present and folder in (:folders) " +
                          "order by title limit :count offset :offset", rowMapper, args);
    }

    public MediaFile getArtistByName(final String name, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return null;
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.DIRECTORY.name());
        args.put("name", name);
        args.put("folders", MusicFolder.toPathList(musicFolders));
        return namedQueryOne("select " + QUERY_COLUMNS + " from media_file where type = :type and artist = :name " +
                             "and present and folder in (:folders)", rowMapper, args);
    }

    /**
     * Creates or updates a media file.
     *
     * @param file The media file to create/update.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void createOrUpdateMediaFile(MediaFile file) {
        LOG.trace("Creating/Updating new media file at {}", file.getPath());
        String sql = "update media_file set " +
                     "folder=?," +
                     "type=?," +
                     "format=?," +
                     "title=?," +
                     "album=?," +
                     "artist=?," +
                     "album_artist=?," +
                     "disc_number=?," +
                     "track_number=?," +
                     "year=?," +
                     "genre=?," +
                     "bit_rate=?," +
                     "variable_bit_rate=?," +
                     "duration=?," +
                     "file_size=?," +
                     "width=?," +
                     "height=?," +
                     "cover_art_path=?," +
                     "parent_path=?," +
                     "play_count=?," +
                     "last_played=?," +
                     "comment=?," +
                     "changed=?," +
                     "last_scanned=?," +
                     "children_last_updated=?," +
                     "present=?, " +
                     "version=?, " +
                     "mb_release_id=?, " +
                     "mb_recording_id=? " +
                     "where path=?";

        LOG.trace("Updating media file {}", Util.debugObject(file));

        int n = update(sql,
                       file.getFolder(), file.getMediaType().name(), file.getFormat(), file.getTitle(), file.getAlbumName(), file.getArtist(),
                       file.getAlbumArtist(), file.getDiscNumber(), file.getTrackNumber(), file.getYear(), file.getGenre(), file.getBitRate(),
                file.isVariableBitRate(), file.getDuration(), file.getFileSize(), file.getWidth(), file.getHeight(),
                       file.getCoverArtPath(), file.getParentPath(), file.getPlayCount(), file.getLastPlayed(), file.getComment(),
                       file.getChanged(), file.getLastScanned(), file.getChildrenLastUpdated(), file.isPresent(), VERSION,
                       file.getMusicBrainzReleaseId(), file.getMusicBrainzRecordingId(), file.getPath());

        if (n == 0) {

            // Copy values from obsolete table music_file_info.
            MediaFile musicFileInfo = getMusicFileInfo(file.getPath());
            if (musicFileInfo != null) {
                file.setComment(musicFileInfo.getComment());
                file.setLastPlayed(musicFileInfo.getLastPlayed());
                file.setPlayCount(musicFileInfo.getPlayCount());
            }

            update("insert into media_file (" + INSERT_COLUMNS + ") values (" + questionMarks(INSERT_COLUMNS) + ")",
                   file.getPath(), file.getFolder(), file.getMediaType().name(), file.getFormat(), file.getTitle(), file.getAlbumName(), file.getArtist(),
                   file.getAlbumArtist(), file.getDiscNumber(), file.getTrackNumber(), file.getYear(), file.getGenre(), file.getBitRate(),
                   file.isVariableBitRate(), file.getDuration(), file.getFileSize(), file.getWidth(), file.getHeight(),
                   file.getCoverArtPath(), file.getParentPath(), file.getPlayCount(), file.getLastPlayed(), file.getComment(),
                   file.getCreated(), file.getChanged(), file.getLastScanned(),
                   file.getChildrenLastUpdated(), file.isPresent(), VERSION, file.getMusicBrainzReleaseId(), file.getMusicBrainzRecordingId());
        }

        int id = queryForInt("select id from media_file where path=?", null, file.getPath());
        file.setId(id);
    }

    private MediaFile getMusicFileInfo(String path) {
        return queryOne("select play_count, last_played, comment from music_file_info where path=?", musicFileInfoRowMapper, path);
    }

    public void deleteMediaFile(String path) {
        deleteMediaFiles(Collections.singletonList(path));
    }

    public void deleteMediaFiles(Collection<String> paths) {
        if (!paths.isEmpty()) {
            batchedUpdate("update media_file set present=false, children_last_updated=? where path=?",
                    paths.parallelStream().map(p -> new Object[] { Instant.ofEpochMilli(1), p }).collect(Collectors.toList()));
        }
    }

    public List<Genre> getGenres(boolean sortByAlbum) {
        String orderBy = sortByAlbum ? "album_count" : "song_count";
        return query("select " + GENRE_COLUMNS + " from genre order by " + orderBy + ", name desc", genreRowMapper);
    }

    public boolean updateGenres(List<Genre> genres) {
        update("delete from genre");
        if (!genres.isEmpty()) {
            return batchedUpdate("insert into genre(" + GENRE_COLUMNS + ") values(?, ?, ?)",
                    genres.parallelStream()
                            .map(genre -> new Object[] { genre.getName(), genre.getSongCount(), genre.getAlbumCount() })
                            .collect(Collectors.toList()))
                == genres.size();
        }

        return true;
    }

    /**
     * Returns the most frequently played albums.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param musicFolders Only return albums in these folders.
     * @return The most frequently played albums.
     */
    public List<MediaFile> getMostFrequentlyPlayedAlbums(final int offset, final int count, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("count", count);
        args.put("offset", offset);

        return namedQuery("select " + QUERY_COLUMNS
                          + " from media_file where type = :type and play_count > 0 and present and folder in (:folders) " +
                          "order by play_count desc, id limit :count offset :offset", rowMapper, args);
    }

    /**
     * Returns the most recently played albums.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param musicFolders Only return albums in these folders.
     * @return The most recently played albums.
     */
    public List<MediaFile> getMostRecentlyPlayedAlbums(final int offset, final int count, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("count", count);
        args.put("offset", offset);
        return namedQuery("select " + QUERY_COLUMNS
                          + " from media_file where type = :type and last_played is not null and present " +
                          "and folder in (:folders) order by last_played desc, id limit :count offset :offset", rowMapper, args);
    }

    /**
     * Returns the most recently added albums.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param musicFolders Only return albums in these folders.
     * @return The most recently added albums.
     */
    public List<MediaFile> getNewestAlbums(final int offset, final int count, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("count", count);
        args.put("offset", offset);

        return namedQuery("select " + QUERY_COLUMNS
                          + " from media_file where type = :type and folder in (:folders) and present " +
                          "order by created desc, id limit :count offset :offset", rowMapper, args);
    }

    /**
     * Returns albums in alphabetical order.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param byArtist     Whether to sort by artist name
     * @param musicFolders Only return albums in these folders.
     * @return Albums in alphabetical order.
     */
    public List<MediaFile> getAlphabeticalAlbums(final int offset, final int count, boolean byArtist, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("count", count);
        args.put("offset", offset);

        String orderBy = byArtist ? "artist, album" : "album";
        return namedQuery("select " + QUERY_COLUMNS
                          + " from media_file where type = :type and folder in (:folders) and present " +
                          "order by " + orderBy + ", id limit :count offset :offset", rowMapper, args);
    }

    /**
     * Returns albums within a year range.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param fromYear     The first year in the range.
     * @param toYear       The last year in the range.
     * @param musicFolders Only return albums in these folders.
     * @return Albums in the year range.
     */
    public List<MediaFile> getAlbumsByYear(final int offset, final int count, final int fromYear, final int toYear,
                                           final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("fromYear", fromYear);
        args.put("toYear", toYear);
        args.put("count", count);
        args.put("offset", offset);

        if (fromYear <= toYear) {
            return namedQuery("select " + QUERY_COLUMNS
                              + " from media_file where type = :type and folder in (:folders) and present " +
                              "and year between :fromYear and :toYear order by year, id limit :count offset :offset",
                              rowMapper, args);
        } else {
            return namedQuery("select " + QUERY_COLUMNS
                              + " from media_file where type = :type and folder in (:folders) and present " +
                              "and year between :toYear and :fromYear order by year desc, id limit :count offset :offset",
                              rowMapper, args);
        }
    }

    /**
     * Returns albums in a genre.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param genre        The genre name.
     * @param musicFolders Only return albums in these folders.
     * @return Albums in the genre.
     */
    public List<MediaFile> getAlbumsByGenre(final int offset, final int count, final String genre,
                                            final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("genre", genre);
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("count", count);
        args.put("offset", offset);
        return namedQuery("select " + QUERY_COLUMNS + " from media_file where type = :type and folder in (:folders) " +
                          "and present and genre = :genre order by id limit :count offset :offset", rowMapper, args);
    }

    public List<MediaFile> getSongsByGenre(final String genre, final int offset, final int count, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("types", Arrays.asList(MediaFile.MediaType.MUSIC.name(), MediaFile.MediaType.PODCAST.name(), MediaFile.MediaType.AUDIOBOOK.name()));
        args.put("genre", genre);
        args.put("count", count);
        args.put("offset", offset);
        args.put("folders", MusicFolder.toPathList(musicFolders));
        return namedQuery("select " + QUERY_COLUMNS + " from media_file where type in (:types) and genre = :genre " +
                          "and present and folder in (:folders) order by id limit :count offset :offset",
                          rowMapper, args);
    }

    public List<MediaFile> getSongsByArtist(String artist, int offset, int count) {
        return query("select " + QUERY_COLUMNS
                     + " from media_file where type in (?,?,?) and artist=? and present order by id limit ? offset ?",
                     rowMapper, MediaFile.MediaType.MUSIC.name(), MediaFile.MediaType.PODCAST.name(), MediaFile.MediaType.AUDIOBOOK.name(), artist, count, offset);
    }

    public MediaFile getSongByArtistAndTitle(final String artist, final String title, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty() || StringUtils.isBlank(title) || StringUtils.isBlank(artist)) {
            return null;
        }
        Map<String, Object> args = new HashMap<>();
        args.put("artist", artist);
        args.put("title", title);
        args.put("type", MediaFile.MediaType.MUSIC.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        return namedQueryOne("select " + QUERY_COLUMNS + " from media_file where artist = :artist " +
                             "and title = :title and type = :type and present and folder in (:folders)",
                             rowMapper, args);
    }

    /**
     * Returns the most recently starred albums.
     *
     * @param offset       Number of albums to skip.
     * @param count        Maximum number of albums to return.
     * @param username     Returns albums starred by this user.
     * @param musicFolders Only return albums in these folders.
     * @return The most recently starred albums for this user.
     */
    public List<MediaFile> getStarredAlbums(final int offset, final int count, final String username,
                                            final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("username", username);
        args.put("count", count);
        args.put("offset", offset);
        return namedQuery("select " + prefix(QUERY_COLUMNS, "media_file") + " from starred_media_file, media_file where media_file.id = starred_media_file.media_file_id and " +
                          "media_file.present and media_file.type = :type and media_file.folder in (:folders) and starred_media_file.username = :username " +
                          "order by starred_media_file.created desc, starred_media_file.id limit :count offset :offset",
                          rowMapper, args);
    }

    /**
     * Returns the most recently starred directories.
     *
     * @param offset       Number of directories to skip.
     * @param count        Maximum number of directories to return.
     * @param username     Returns directories starred by this user.
     * @param musicFolders Only return albums in these folders.
     * @return The most recently starred directories for this user.
     */
    public List<MediaFile> getStarredDirectories(final int offset, final int count, final String username,
                                                 final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.DIRECTORY.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("username", username);
        args.put("count", count);
        args.put("offset", offset);
        return namedQuery("select " + prefix(QUERY_COLUMNS, "media_file") + " from starred_media_file, media_file " +
                          "where media_file.id = starred_media_file.media_file_id and " +
                          "media_file.present and media_file.type = :type and starred_media_file.username = :username and " +
                          "media_file.folder in (:folders) " +
                          "order by starred_media_file.created desc, starred_media_file.id limit :count offset :offset",
                          rowMapper, args);
    }

    /**
     * Returns the most recently starred files.
     *
     * @param offset       Number of files to skip.
     * @param count        Maximum number of files to return.
     * @param username     Returns files starred by this user.
     * @param musicFolders Only return albums in these folders.
     * @return The most recently starred files for this user.
     */
    public List<MediaFile> getStarredFiles(final int offset, final int count, final String username,
                                           final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> args = new HashMap<>();
        args.put("types", Arrays.asList(MediaFile.MediaType.MUSIC.name(), MediaFile.MediaType.PODCAST.name(), MediaFile.MediaType.AUDIOBOOK.name(), MediaFile.MediaType.VIDEO.name()));
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("username", username);
        args.put("count", count);
        args.put("offset", offset);
        return namedQuery("select " + prefix(QUERY_COLUMNS, "media_file") + " from starred_media_file, media_file where media_file.id = starred_media_file.media_file_id and " +
                          "media_file.present and media_file.type in (:types) and starred_media_file.username = :username and " +
                          "media_file.folder in (:folders) " +
                          "order by starred_media_file.created desc, starred_media_file.id limit :count offset :offset",
                          rowMapper, args);
    }

    public List<MediaFile> getRandomSongs(RandomSearchCriteria criteria, final String username) {
        if (criteria.getMusicFolders().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Object> args = new HashMap<>();
        args.put("folders", MusicFolder.toPathList(criteria.getMusicFolders()));
        args.put("username", username);
        args.put("fromYear", criteria.getFromYear());
        args.put("toYear", criteria.getToYear());
        args.put("genre", criteria.getGenre());
        args.put("minLastPlayed", criteria.getMinLastPlayedDate());
        args.put("maxLastPlayed", criteria.getMaxLastPlayedDate());
        args.put("minAlbumRating", criteria.getMinAlbumRating());
        args.put("maxAlbumRating", criteria.getMaxAlbumRating());
        args.put("minPlayCount", criteria.getMinPlayCount());
        args.put("maxPlayCount", criteria.getMaxPlayCount());
        args.put("starred", criteria.isShowStarredSongs());
        args.put("unstarred", criteria.isShowUnstarredSongs());
        args.put("format", criteria.getFormat());

        boolean joinAlbumRating = (criteria.getMinAlbumRating() != null || criteria.getMaxAlbumRating() != null);
        boolean joinStarred = (criteria.isShowStarredSongs() ^ criteria.isShowUnstarredSongs());

        String query = "select " + prefix(QUERY_COLUMNS, "media_file") + " from media_file ";

        if (joinStarred) {
            query += "left outer join starred_media_file on media_file.id = starred_media_file.media_file_id and starred_media_file.username = :username ";
        }

        if (joinAlbumRating) {
            query += "left outer join media_file media_album on media_album.type = 'ALBUM' and media_album.album = media_file.album and media_album.artist = media_file.artist ";
            query += "left outer join user_rating on user_rating.path = media_album.path and user_rating.username = :username ";
        }

        query += " where media_file.present and media_file.type = 'MUSIC'";

        if (!criteria.getMusicFolders().isEmpty()) {
            query += " and media_file.folder in (:folders)";
        }

        if (criteria.getGenre() != null) {
            query += " and media_file.genre = :genre";
        }

        if (criteria.getFormat() != null) {
            query += " and media_file.format = :format";
        }

        if (criteria.getFromYear() != null) {
            query += " and media_file.year >= :fromYear";
        }

        if (criteria.getToYear() != null) {
            query += " and media_file.year <= :toYear";
        }

        if (criteria.getMinLastPlayedDate() != null) {
            query += " and media_file.last_played >= :minLastPlayed";
        }

        if (criteria.getMaxLastPlayedDate() != null) {
            if (criteria.getMinLastPlayedDate() == null) {
                query += " and (media_file.last_played is null or media_file.last_played <= :maxLastPlayed)";
            } else {
                query += " and media_file.last_played <= :maxLastPlayed";
            }
        }

        if (criteria.getMinAlbumRating() != null) {
            query += " and user_rating.rating >= :minAlbumRating";
        }

        if (criteria.getMaxAlbumRating() != null) {
            if (criteria.getMinAlbumRating() == null) {
                query += " and (user_rating.rating is null or user_rating.rating <= :maxAlbumRating)";
            } else {
                query += " and user_rating.rating <= :maxAlbumRating";
            }
        }

        if (criteria.getMinPlayCount() != null) {
            query += " and media_file.play_count >= :minPlayCount";
        }

        if (criteria.getMaxPlayCount() != null) {
            if (criteria.getMinPlayCount() == null) {
                query += " and (media_file.play_count is null or media_file.play_count <= :maxPlayCount)";
            } else {
                query += " and media_file.play_count <= :maxPlayCount";
            }
        }

        if (criteria.isShowStarredSongs() && !criteria.isShowUnstarredSongs()) {
            query += " and starred_media_file.id is not null";
        }

        if (criteria.isShowUnstarredSongs() && !criteria.isShowStarredSongs()) {
            query += " and starred_media_file.id is null";
        }

        query += " order by rand()";

        query += " limit " + criteria.getCount();

        return namedQuery(query, rowMapper, args);
    }

    public int getAlbumCount(final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return 0;
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        return namedQueryForInt("select count(*) from media_file where type = :type and folder in (:folders) and present", 0, args);
    }

    public int getPlayedAlbumCount(final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return 0;
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        return namedQueryForInt("select count(*) from media_file where type = :type " +
                                "and play_count > 0 and present and folder in (:folders)", 0, args);
    }

    public int getStarredAlbumCount(final String username, final List<MusicFolder> musicFolders) {
        if (musicFolders.isEmpty()) {
            return 0;
        }
        Map<String, Object> args = new HashMap<>();
        args.put("type", MediaFile.MediaType.ALBUM.name());
        args.put("folders", MusicFolder.toPathList(musicFolders));
        args.put("username", username);
        return namedQueryForInt("select count(*) from starred_media_file, media_file " +
                                "where media_file.id = starred_media_file.media_file_id " +
                                "and media_file.type = :type " +
                                "and media_file.present " +
                                "and media_file.folder in (:folders) " +
                                "and starred_media_file.username = :username",
                                0, args);
    }

    public void starMediaFiles(List<Integer> ids, String username) {
        if (!ids.isEmpty()) {
            unstarMediaFiles(ids, username);
            Instant now = Instant.now();
            batchedUpdate("insert into starred_media_file(media_file_id, username, created) values (?,?,?)",
                    ids.parallelStream().map(id -> new Object[] { id, username, now }).collect(Collectors.toList()));
        }
    }

    public void unstarMediaFiles(List<Integer> ids, String username) {
        if (!ids.isEmpty()) {
            namedUpdate("delete from starred_media_file where media_file_id in (:ids) and username=:user",
                    ImmutableMap.of("ids", ids, "user", username));
        }
    }

    public void starMediaFile(int id, String username) {
        starMediaFiles(Collections.singletonList(id), username);
    }

    public void unstarMediaFile(int id, String username) {
        unstarMediaFiles(Collections.singletonList(id), username);
    }

    public Instant getMediaFileStarredDate(int id, String username) {
        return queryForInstant("select created from starred_media_file where media_file_id=? and username=?", null, id, username);
    }

    public boolean markPresent(String path, Instant lastScanned) {
        return markPresent(Collections.singletonList(path), lastScanned);
    }

    public boolean markPresent(Collection<String> paths, Instant lastScanned) {
        if (!paths.isEmpty()) {
            int batches = (paths.size() - 1) / 30000;
            List<String> pList = new ArrayList<>(paths);
            return IntStream.rangeClosed(0, batches).parallel().map(b -> {
                List<String> batch = pList.subList(b * 30000, Math.min(paths.size(), b * 30000 + 30000));
                return namedUpdate(
                        "update media_file set present=true, last_scanned = :lastScanned where path in (:paths)",
                        ImmutableMap.of("lastScanned", lastScanned, "paths", batch));
            }).sum() == paths.size();
        }

        return true;
    }

    public void markNonPresent(Instant lastScanned) {
        Instant childrenLastUpdated = Instant.ofEpochMilli(1);  // Used to force a children rescan if file is later resurrected.

        update("update media_file set present=false, children_last_updated=? where last_scanned < ? and present",
                childrenLastUpdated, lastScanned);
    }

    public List<Integer> getArtistExpungeCandidates() {
        return queryForInts("select id from media_file where media_file.type = ? and not present",
                MediaFile.MediaType.DIRECTORY.name());
    }

    public List<Integer> getAlbumExpungeCandidates() {
        return queryForInts("select id from media_file where media_file.type = ? and not present",
                MediaFile.MediaType.ALBUM.name());
    }

    public List<Integer> getSongExpungeCandidates() {
        return queryForInts("select id from media_file where media_file.type in (?,?,?,?) and not present",
                MediaFile.MediaType.MUSIC.name(), MediaFile.MediaType.PODCAST.name(),
                MediaFile.MediaType.AUDIOBOOK.name(), MediaFile.MediaType.VIDEO.name());
    }

    public void expunge() {
        update("delete from media_file where not present");
    }

    private static class MediaFileMapper implements RowMapper<MediaFile> {
        @Override
        public MediaFile mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MediaFile(
                    rs.getInt(1),
                    rs.getString(2),
                    rs.getString(3),
                    MediaFile.MediaType.valueOf(rs.getString(4)),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getInt(10) == 0 ? null : rs.getInt(10),
                    rs.getInt(11) == 0 ? null : rs.getInt(11),
                    rs.getInt(12) == 0 ? null : rs.getInt(12),
                    rs.getString(13),
                    rs.getInt(14) == 0 ? null : rs.getInt(14),
                    rs.getBoolean(15),
                    rs.getDouble(16),
                    rs.getLong(17) == 0 ? null : rs.getLong(17),
                    rs.getInt(18) == 0 ? null : rs.getInt(18),
                    rs.getInt(19) == 0 ? null : rs.getInt(19),
                    rs.getString(20),
                    rs.getString(21),
                    rs.getInt(22),
                    Optional.ofNullable(rs.getTimestamp(23)).map(x -> x.toInstant()).orElse(null),
                    rs.getString(24),
                    Optional.ofNullable(rs.getTimestamp(25)).map(x -> x.toInstant()).orElse(null),
                    Optional.ofNullable(rs.getTimestamp(26)).map(x -> x.toInstant()).orElse(null),
                    Optional.ofNullable(rs.getTimestamp(27)).map(x -> x.toInstant()).orElse(null),
                    Optional.ofNullable(rs.getTimestamp(28)).map(x -> x.toInstant()).orElse(null),
                    rs.getBoolean(29),
                    rs.getInt(30),
                    rs.getString(31),
                    rs.getString(32));
        }
    }

    private static class MusicFileInfoMapper implements RowMapper<MediaFile> {
        @Override
        public MediaFile mapRow(ResultSet rs, int rowNum) throws SQLException {
            MediaFile file = new MediaFile();
            file.setPlayCount(rs.getInt(1));
            file.setLastPlayed(Optional.ofNullable(rs.getTimestamp(2)).map(x -> x.toInstant()).orElse(null));
            file.setComment(rs.getString(3));
            return file;
        }
    }

    private static class GenreMapper implements RowMapper<Genre> {
        @Override
        public Genre mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Genre(rs.getString(1), rs.getInt(2), rs.getInt(3));
        }
    }
}
