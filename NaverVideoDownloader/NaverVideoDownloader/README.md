# 영상 다운로더 (개인용)

네이버 뉴스 기사(`n.news.naver.com`) 페이지 내 임베디드 HLS(m3u8) 스트리밍 영상을
감지하여 최고 화질로 다운로드하는 개인용 Android 앱입니다.

## 휴대폰만으로 빌드/설치하기 (PC 불필요)

이 저장소에는 `.github/workflows/build-debug-apk.yml`이 포함되어 있어,
GitHub에 올리기만 하면 GitHub의 클라우드 서버가 자동으로 APK를 빌드해 줍니다.
휴대폰의 모바일 브라우저(또는 GitHub 앱)만으로 아래 순서를 진행하면 됩니다.

1. 휴대폰의 파일 관리 앱에서 다운로드한 zip 파일의 압축을 풉니다.
2. 모바일 브라우저에서 github.com 접속 후 로그인, 새 저장소(Repository)를 생성합니다.
   (Public/Private 무관, 이름은 자유롭게)
3. 저장소 페이지에서 "Add file" → "Upload files"를 눌러 압축 해제된
   `NaverVideoDownloader` 폴더 안의 모든 파일/폴더를 업로드합니다.
   - 모바일 브라우저는 폴더 단위 업로드가 막혀 있는 경우가 있습니다.
     이 경우 브라우저를 "데스크톱 모드"로 전환하면 폴더 선택이 가능해집니다.
4. 업로드 후 커밋(Commit changes)하면 `.github/workflows` 안의 워크플로우가
   자동으로 실행됩니다. 저장소 상단 "Actions" 탭에서 진행 상황을 볼 수 있습니다.
   자동 실행되지 않으면 Actions 탭 → "Build Debug APK" → "Run workflow"를 직접 누릅니다.
5. 빌드가 끝나면(수 분 소요) 해당 실행 결과 페이지 하단 "Artifacts"에
   `NaverVideoDownloader-debug-apk`가 생성됩니다. 이를 다운로드하면 zip 안에
   `app-debug.apk`가 들어 있습니다.
6. 휴대폰 파일 관리 앱에서 `app-debug.apk`를 눌러 설치합니다.
   최초 설치 시 "출처를 알 수 없는 앱 설치 허용"을 요청하면 허용해야 합니다.

이 방식은 서명되지 않은 디버그(Debug) APK이며 개인 테스트 목적에는 충분합니다.

## PC에서 Android Studio로 빌드하는 방법

1. Android Studio (최신 버전 권장)에서 이 폴더(`NaverVideoDownloader`)를 "Open" 합니다.
2. Gradle Sync가 자동으로 진행됩니다. `gradlew` 실행 파일이 없다는 안내가 뜨면
   Android Studio가 제공하는 "Create Gradle Wrapper" 옵션을 사용하거나,
   File > Sync Project with Gradle Files 를 실행하면 자동 생성됩니다.
3. 휴대폰을 USB 디버깅 모드로 연결하고 Run ▶ 버튼으로 설치합니다.

## 사용 방법

1. 앱 상단 입력창에 기사 URL을 입력하고 "불러오기"를 누릅니다.
   예: `https://n.news.naver.com/article/055/0001378975?type=journalists`
2. 아래 WebView에 실제 페이지가 로드됩니다. 영상이 자동 재생되지 않는 사이트라면
   화면 내 재생 버튼을 직접 눌러 스트리밍을 시작시켜야 합니다.
   (재생이 시작되어야 m3u8 요청이 발생하여 감지됩니다.)
3. 하단 로그창에 "m3u8 감지"가 표시되고 "최고화질 다운로드" 버튼이 활성화되면 버튼을 누릅니다.
4. 앱이 마스터 재생목록을 분석해 가장 높은 해상도의 스트림을 선택하고,
   세그먼트(.ts)를 순서대로 내려받아 하나의 파일로 합칩니다.
5. 완료되면 아래 경로에 `.ts` 파일로 저장됩니다.
   `/sdcard/Android/data/com.rus.videodownloader/files/Movies/video_YYYYMMDD_HHMMSS.ts`
   (앱 전용 저장소라 별도 권한 요청 없이 저장됩니다.)

## 구현 세부 사항

- **탐지 방식**: `WebViewClient.shouldInterceptRequest`로 페이지가 실제 브라우저처럼
  로드되는 과정에서 발생하는 모든 리소스 요청을 감청하여 `.m3u8` 포함 URL을 수집합니다.
- **화질 선택**: 마스터 재생목록의 `#EXT-X-STREAM-INF` 태그에서 `RESOLUTION`/`BANDWIDTH`를
  파싱해 해상도(가로×세로) 기준으로 최고 화질을 선택합니다.
- **인증 우회 방지**: 실제 요청 시 WebView 세션의 쿠키(`CookieManager`)와 User-Agent를
  그대로 실어 보내 핫링크 차단이나 세션 검증에 걸리지 않도록 했습니다.
- **파일 형식**: `.ts` 세그먼트를 그대로 이어붙인 MPEG-TS 파일입니다. VLC, MX Player 등
  대부분의 플레이어에서 바로 재생됩니다. 범용 `.mp4`로 변환하려면 PC에서
  `ffmpeg -i video.ts -c copy video.mp4` 명령으로 재인코딩 없이 컨테이너만 변경하면 됩니다.

## 알려진 제한 사항

- 사이트가 DASH(mpd) 방식으로 전환하거나 m3u8 URL에 별도 토큰 검증을 강화하면
  현재 로직으로는 탐지/다운로드가 실패할 수 있습니다. 이 경우 `MainActivity.kt`의
  `shouldInterceptRequest` 필터 조건을 확장해야 합니다.
- 영상 재생이 사용자의 탭(터치) 조작을 요구하는 사이트는 자동 재생되지 않으므로
  WebView 화면에서 직접 재생 버튼을 눌러야 스트림이 감지됩니다.
- 저작권이 있는 콘텐츠를 무단으로 배포하는 용도로 사용하지 않도록 주의가 필요합니다.
  본 앱은 개인 시청/보관 목적의 개인용 도구로만 사용하는 것을 전제로 합니다.
