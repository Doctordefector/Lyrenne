-- MetroList 14.x fixture library (KMP rewrite, Room schema 65). Likes live in settings.json.

INSERT INTO artist (id,name,thumbnailUrl,channelId,lastUpdateTime,bookmarkedAt,isLocal,isPodcastChannel) VALUES
 ('UCiMhD4jzUqG-IgPzUmmytRQ','Queen','https://lh3.googleusercontent.com/queen',NULL,1704067200000,1714564800000,0,0),
 ('UCWzwfD0NGLZ-ePBTGOpu7hg','Daft Punk','https://lh3.googleusercontent.com/daft',NULL,1704067200000,1717317000000,0,0),
 ('UCvpredjG93ifbCP1Y77JyFA','YOASOBI',NULL,NULL,1704067200000,NULL,0,0),
 ('LAa1b2c3d4','Garage Band Demo',NULL,NULL,1704067200000,1719792000000,1,0),
 ('UCpodcast00000000000000','Some Podcast',NULL,NULL,1704067200000,1719792000000,0,1);

INSERT INTO song (id,title,customTitle,customArtist,duration,thumbnailUrl,albumId,albumName,explicit,year,date,dateModified,liked,likedDate,totalPlayTime,inLibrary,dateDownload,isLocal,libraryAddToken,libraryRemoveToken,lyricsOffset,romanizeLyrics,isDownloaded,isUploaded,isVideo,isEpisode,playbackPosition,uploadEntityId,isCached,explicitness,isUnavailable) VALUES
 ('fJ9rUzIMcZQ','Bohemian Rhapsody','Bo Rhap',NULL,355,'https://i.ytimg.com/vi/fJ9rUzIMcZQ/hq.jpg','MPREb_opera','A Night at the Opera (2011 Remaster)',0,1975,NULL,NULL,0,NULL,600000,1740823200000,NULL,0,NULL,NULL,0,1,0,0,0,0,NULL,NULL,0,NULL,0),
 ('FGBhQbmPwH8','One More Time',NULL,NULL,320,NULL,'MPREb_disc','Discovery',0,2001,NULL,NULL,1,1744752600000,320000,NULL,NULL,0,NULL,NULL,0,1,0,0,0,0,NULL,NULL,0,NULL,0),
 ('x8VYWazR5mE','夜に駆ける',NULL,NULL,261,NULL,NULL,NULL,0,2019,NULL,NULL,0,NULL,0,NULL,NULL,0,NULL,NULL,0,1,0,0,0,0,NULL,NULL,0,NULL,0),
 ('file:///storage/emulated/0/Music/demo.mp3','demo',NULL,NULL,61,NULL,NULL,NULL,0,NULL,NULL,NULL,0,NULL,0,NULL,NULL,1,NULL,NULL,0,1,0,0,0,0,NULL,NULL,0,NULL,0);
INSERT INTO song_artist_map (songId,artistId,position) VALUES
 ('fJ9rUzIMcZQ','UCiMhD4jzUqG-IgPzUmmytRQ',0),
 ('FGBhQbmPwH8','UCWzwfD0NGLZ-ePBTGOpu7hg',0),
 ('x8VYWazR5mE','UCvpredjG93ifbCP1Y77JyFA',0);
INSERT INTO album (id,playlistId,title,year,thumbnailUrl,themeColor,songCount,duration,explicit,lastUpdateTime,bookmarkedAt,likedDate,inLibrary,isLocal,isUploaded) VALUES
 ('MPREb_opera','OLAK5uy_opera','A Night at the Opera (2011 Remaster)',1975,NULL,NULL,12,2580,0,1704067200000,NULL,1740909600000,NULL,0,0);
INSERT INTO playlist (id,name,browseId,createdAt,lastUpdateTime,isEditable,bookmarkedAt,remoteSongCount,playEndpointParams,thumbnailUrl,shuffleEndpointParams,radioEndpointParams,isLocal,isAutoSync,description,customThumbnailUrl) VALUES
 ('LPq7Wm2Zx1','Late night',NULL,1748818800000,NULL,1,NULL,NULL,NULL,NULL,NULL,NULL,1,0,'for the drive home',NULL);
INSERT INTO playlist_song_map (playlistId,songId,position,setVideoId) VALUES
 ('LPq7Wm2Zx1','x8VYWazR5mE',0,NULL),
 ('LPq7Wm2Zx1','FGBhQbmPwH8',1,NULL);
INSERT INTO event (songId,timestamp,playTime,playTimeKnown,source,remoteFeedbackToken,remoteSynced,remoteDateLabel,remoteAccountKey,remoteOrder) VALUES
 ('fJ9rUzIMcZQ',1740988800000,355000,1,'LOCAL',NULL,0,NULL,NULL,0),
 ('FGBhQbmPwH8',1744786800000,320000,1,'LOCAL',NULL,0,NULL,NULL,0),
 ('x8VYWazR5mE',1746057600000,0,0,'YOUTUBE','token',1,'Today','acct',0);
INSERT INTO lyrics (id,lyrics,provider,translatedLyrics,translationLanguage,translationMode,translationSourceHash,translationConfigKey,translationNotNeeded,offsetMs,isUserSelected,userLyrics,userLyricsDraft,stashedUserLyrics,stashedUserLyricsDraft,missLookupKey,missExpiresAt) VALUES
 ('FGBhQbmPwH8','[00:30.00]cached','LrcLib','','','','','',0,0,0,'[00:30.00]One more time (my edit)',NULL,NULL,NULL,NULL,NULL),
 ('x8VYWazR5mE','[00:01.00]沈むように','BetterLyrics','','','','','',0,-250,1,NULL,NULL,NULL,NULL,NULL,NULL);
