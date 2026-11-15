-- MetroList 13.x fixture library. Dates are Room's: wall-clock time written as UTC epoch ms.

INSERT INTO artist (id,name,thumbnailUrl,channelId,lastUpdateTime,bookmarkedAt,isLocal,isPodcastChannel) VALUES
 ('UCiMhD4jzUqG-IgPzUmmytRQ','Queen','https://lh3.googleusercontent.com/queen',NULL,1704067200000,1714564800000,0,0),
 ('UCWzwfD0NGLZ-ePBTGOpu7hg','Daft Punk','https://lh3.googleusercontent.com/daft',NULL,1704067200000,1717317000000,0,0),
 ('UCvpredjG93ifbCP1Y77JyFA','YOASOBI',NULL,NULL,1704067200000,NULL,0,0),
 ('LAa1b2c3d4','Garage Band Demo',NULL,NULL,1704067200000,1719792000000,1,0),
 ('UCpodcast00000000000000','Some Podcast',NULL,NULL,1704067200000,1719792000000,0,1);

INSERT INTO song (id,title,duration,thumbnailUrl,albumId,albumName,explicit,year,date,dateModified,liked,likedDate,totalPlayTime,inLibrary,dateDownload,isLocal,libraryAddToken,libraryRemoveToken,lyricsOffset,romanizeLyrics,isDownloaded,isUploaded,isVideo,isEpisode,playbackPosition,uploadEntityId) VALUES
 ('fJ9rUzIMcZQ','Bohemian Rhapsody',355,'https://i.ytimg.com/vi/fJ9rUzIMcZQ/hq.jpg','MPREb_opera','A Night at the Opera (2011 Remaster)',0,1975,NULL,NULL,1,1740823200000,1200000,1740823200000,NULL,0,NULL,NULL,0,1,0,0,0,0,NULL,NULL),
 ('FGBhQbmPwH8','One More Time',320,'https://i.ytimg.com/vi/FGBhQbmPwH8/hq.jpg','MPREb_disc','Discovery',0,2001,NULL,NULL,1,1744752600000,600000,1744752600000,NULL,0,NULL,NULL,500,1,0,0,0,0,NULL,NULL),
 ('x8VYWazR5mE','夜に駆ける',261,'https://i.ytimg.com/vi/x8VYWazR5mE/hq.jpg',NULL,NULL,0,2019,NULL,NULL,0,NULL,0,1739178000000,NULL,0,NULL,NULL,0,1,0,0,0,0,NULL,NULL),
 ('GDpmVUEjagg','Harder, Better, Faster, Stronger',224,NULL,'MPREb_disc','Discovery',0,2001,NULL,NULL,0,NULL,0,NULL,NULL,0,NULL,NULL,0,1,0,0,0,0,NULL,NULL),
 ('content://media/external/audio/media/42','My Voice Memo',61,NULL,NULL,NULL,0,NULL,NULL,NULL,1,1735689600000,0,1735689600000,NULL,1,NULL,NULL,0,1,0,0,0,0,NULL,NULL),
 ('dQw4w9WgXcQ','Never Gonna Give You Up',213,NULL,NULL,NULL,0,1987,NULL,NULL,0,NULL,0,NULL,NULL,0,NULL,NULL,0,1,0,0,1,0,NULL,NULL);
INSERT INTO song_artist_map (songId,artistId,position) VALUES
 ('fJ9rUzIMcZQ','UCiMhD4jzUqG-IgPzUmmytRQ',0),
 ('FGBhQbmPwH8','UCWzwfD0NGLZ-ePBTGOpu7hg',0),
 ('x8VYWazR5mE','UCvpredjG93ifbCP1Y77JyFA',0),
 ('GDpmVUEjagg','UCWzwfD0NGLZ-ePBTGOpu7hg',0),
 ('content://media/external/audio/media/42','LAa1b2c3d4',0);
INSERT INTO album (id,playlistId,title,year,thumbnailUrl,themeColor,songCount,duration,explicit,lastUpdateTime,bookmarkedAt,likedDate,inLibrary,isLocal,isUploaded) VALUES
 ('MPREb_opera','OLAK5uy_opera','A Night at the Opera (2011 Remaster)',1975,'https://lh3.googleusercontent.com/opera',NULL,12,2580,0,1704067200000,1740909600000,NULL,NULL,0,0),
 ('MPREb_disc','OLAK5uy_disc','Discovery',2001,NULL,NULL,14,3660,0,1704067200000,NULL,NULL,NULL,0,0);
INSERT INTO playlist (id,name,browseId,createdAt,lastUpdateTime,isEditable,bookmarkedAt,remoteSongCount,playEndpointParams,thumbnailUrl,shuffleEndpointParams,radioEndpointParams,isLocal,isAutoSync) VALUES
 ('LPa8Kd03Jx','Road trip',NULL,1746122400000,1746122400000,1,NULL,NULL,NULL,NULL,NULL,NULL,0,0),
 ('PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf','Synced from YouTube','PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf',1735689600000,NULL,1,1735689600000,40,NULL,NULL,NULL,NULL,0,0),
 ('LM','Liked Music','LM',NULL,NULL,0,NULL,NULL,NULL,NULL,NULL,NULL,0,0);
INSERT INTO playlist_song_map (playlistId,songId,position,setVideoId) VALUES
 ('LPa8Kd03Jx','FGBhQbmPwH8',0,NULL),
 ('LPa8Kd03Jx','GDpmVUEjagg',1,NULL),
 ('LPa8Kd03Jx','fJ9rUzIMcZQ',2,NULL),
 ('LPa8Kd03Jx','content://media/external/audio/media/42',3,NULL),
 ('PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf','fJ9rUzIMcZQ',0,'setvid1');
INSERT INTO event (songId,timestamp,playTime) VALUES
 ('fJ9rUzIMcZQ',1707940800000,354000),
 ('fJ9rUzIMcZQ',1740823500000,300000),
 ('FGBhQbmPwH8',1733011140000,320000),
 ('dQw4w9WgXcQ',1743508800000,212000),
 ('content://media/external/audio/media/42',1743509400000,61000);
INSERT INTO lyrics (id,lyrics,provider,translatedLyrics,translationLanguage,translationMode) VALUES
 ('fJ9rUzIMcZQ','[00:01.00]Is this the real life?','Manual','','',''),
 ('FGBhQbmPwH8','[00:30.00]One more time','LrcLib','','',''),
 ('x8VYWazR5mE','LYRICS_NOT_FOUND','Manual','','','');
