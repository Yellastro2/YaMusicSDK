# YaMusicSDK

`YaMusicSDK` — самостоятельная Kotlin/JVM-библиотека для работы с API
Яндекс Музыки, созданная на основе знаний и контрактов, собранных в
[MarshalX/yandex-music-api](https://github.com/MarshalX/yandex-music-api).

Модуль предоставляет необходимую приложению `Движ` работу с API Яндекс Музыки:
авторизацию, аккаунт, треки, плейлисты, поиск, «Мою волну», получение ссылок
для воспроизведения и отправку feedback-событий.

> Это неофициальная библиотека. API Яндекс Музыки не является публичным
> стабильным контрактом, поэтому отдельные методы и модели могут перестать
> работать после изменений на стороне сервиса.

## Требования и сборка

- JDK 17 или новее для запуска Gradle 9.1;
- целевая версия библиотеки — JVM 11;
- установленный Gradle 9.1, пока репозиторий не содержит собственного Gradle Wrapper.

Клонирование и проверка отдельного репозитория:

```powershell
git clone https://github.com/Yellastro2/YaMusicSDK.git
cd YaMusicSDK
gradle test
gradle build
```

Библиотека пока не публикуется в Maven-репозитории. В приложении `Движ` она
подключается из исходников как Gradle-модуль `:yaMusicSdk`.

## Возможности

- **Авторизация** — OAuth Device Flow, получение access/refresh token, обновление и очистка авторизации клиента.
- **Аккаунт** — получение статуса и данных текущего аккаунта (`accountStatus`).
- **Лайки** — лайк/дизлайк трека и получение списка понравившихся треков.
- **Плейлисты** — список плейлистов, получение конкретного плейлиста, создание и удаление, добавление и удаление треков.
- **Треки** — получение полной информации о треках по ID.
- **Поиск** — поиск по Яндекс Музыке с типами и страницами, а также поисковые подсказки.
- **«Моя волна» / Rotor** — старт волны, получение следующей пачки треков, feedback-события: запуск радио, старт, завершение и пропуск трека.
- **Воспроизведение / загрузка** — получение download-info, прямого URL аудио и байтов трека.
- **Обложки** — загрузка изображения обложки нужного размера.
- **Телеметрия прослушивания** — `/play-audio`: начало и завершение прослушивания, позиции, длительность, `playId`, контекст плейлиста и другие параметры.

## Авторизация

Для авторизации доступен OAuth Device Flow:

```kotlin
val logger = NoOpYamLogger

val auth = YandexDeviceAuth(
    clientId = oauthClientId,
    clientSecret = oauthClientSecret,
    logger = logger
)

val result = auth.authorize { code ->
    println("Откройте ${code.verificationUrl} и введите ${code.userCode}")
}

when (result) {
    is DeviceAuthResult.Success -> {
        val token = result.value

        // Сохранить token.accessToken
        // и при необходимости token.refreshToken.
    }

    is DeviceAuthResult.Failure -> {
        // Обработать типизированную ошибку.
    }
}
```

Корутину с `authorize` можно отменить штатным способом.

Низкоуровневые методы `requestDeviceCode()` и `pollDeviceToken()` также
доступны отдельно. Pending-ответ при опросе представлен как `Success(null)`.

Хранение полученных токенов остаётся ответственностью вызывающего приложения.

## Клиент API

Основные операции выполняются через `YamApiClient`.

Клиент создаётся с сохранённым access token и идентификатором пользователя:

```kotlin
val client = YamApiClient(
    accessToken = accessToken,
    userId = userId,
    logger = NoOpYamLogger
)
```

Сетевые методы возвращают типизированный `YamResult<T>`:

```kotlin
when (val result = client.accountStatus()) {
    is YamResult.Success -> {
        val account = result.value.account
        println(account?.login)
    }

    is YamResult.Failure -> {
        // Unauthorized, Timeout, NoInternet, Http и другие ошибки.
    }
}
```

## Лайки

```kotlin
val update = client.setTrackLiked(
    trackId = trackId,
    liked = true
)

val likedTracks = client.likedTracks()
```

## Плейлисты

```kotlin
val playlists = client.playlists()

val playlist = client.playlist(
    kind = 1000
)

val created = client.createPlaylist(
    title = "Мой плейлист",
    isPublic = true
)

client.addTrack(
    playlistKind = 1000,
    revision = revision,
    trackId = trackId,
    trackAlbum = albumId
)

client.removeTrack(
    playlistKind = 1000,
    revision = revision,
    trackNumber = trackNumber
)

client.deletePlaylist(
    kind = "1000"
)
```

## Треки

Получение треков по ID:

```kotlin
val tracks = client.tracks(
    listOf("123", "456")
)
```

## Поиск

```kotlin
val result = client.search(
    text = "Muse"
)

val suggestions = client.searchSuggestions(
    part = "Mus"
)
```

Поиск поддерживает выбор типа выдачи, номер страницы и другие параметры.

## Моя волна

Запуск станции:

```kotlin
val wave = client.startWave(
    "user:onyourwave"
)
```

Получение следующей пачки:

```kotlin
val next = client.nextWaveTracks(
    station = "user:onyourwave",
    previousTrackId = previousTrackId
)
```

Для корректной работы радио доступны специализированные feedback-события:

```kotlin
client.sendWaveStarted(
    station = station,
    batchId = batchId
)

client.sendWaveTrackStarted(
    station = station,
    trackId = trackId,
    batchId = batchId
)

client.sendWaveTrackFinished(
    station = station,
    trackId = trackId,
    totalPlayedSeconds = playedSeconds,
    batchId = batchId
)

client.sendWaveTrackSkipped(
    station = station,
    trackId = trackId,
    totalPlayedSeconds = playedSeconds,
    batchId = batchId
)
```

## Воспроизведение и загрузка

Получение информации о доступных вариантах аудио:

```kotlin
val info = client.trackDownloadInfo(
    trackId = "123"
)
```

Получение готовой прямой ссылки:

```kotlin
val directUrl = client.trackDownloadUrl(
    trackId = "123"
)
```

Получение аудиофайла как `ByteArray`:

```kotlin
val bytes = client.trackDownloadBytes(
    trackId = "123"
)
```

## Обложки

```kotlin
val cover = client.coverBytes(
    coverUri,
    CoverSize.`400x400`
)
```

## Телеметрия прослушивания

Для отправки универсальной телеметрии используется `/play-audio`:

```kotlin
val started = client.playAudio(
    PlayAudioRequest(
        trackId = "123",
        albumId = "456",
        source = "dwij-android",
        playId = playId,
        endPositionSeconds = durationSeconds
    )
)
```

При завершении или прерывании воспроизведения отправляется второй запрос с тем
же `playId`, фактическими `totalPlayedSeconds` и `endPositionSeconds`.

`playlistId` указывается только при воспроизведении из плейлиста.

Для радио `/play-audio` дополняет, но не заменяет специализированные Rotor
feedback-события.

## Типизированные результаты

`JSONObject`, `JSONArray` и транспортные ответы не появляются в публичном API.

Сетевые операции возвращают `YamResult<T>`, а транспорт отдельно обрабатывает
JSON API и бинарный контент.

## Происхождение

Проект создан на основе знаний о недокументированном API Яндекс Музыки,
собранных в проекте
[MarshalX/yandex-music-api](https://github.com/MarshalX/yandex-music-api).

`YaMusicSDK` является самостоятельной Kotlin-реализацией и не стремится
повторять структуру Python SDK один к одному.

## Лицензия

`YaMusicSDK` является производной Kotlin-реализацией проекта
[MarshalX/yandex-music-api](https://github.com/MarshalX/yandex-music-api).

Оригинальный проект распространяется под **GNU Lesser General Public License v3.0 (LGPL-3.0)**. `YaMusicSDK` также распространяется под лицензией **LGPL-3.0**.

Вы можете использовать, изменять и распространять библиотеку в соответствии с условиями LGPL-3.0.

Авторские права на оригинальный проект принадлежат его авторам и участникам. Авторские права на изменения и Kotlin-реализацию `YaMusicSDK` принадлежат авторам этого проекта.

При распространении изменённых версий необходимо сохранять уведомления об авторских правах, информацию об исходном проекте и условия лицензии LGPL-3.0.
