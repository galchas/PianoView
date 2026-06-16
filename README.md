![PianoView](./img/piano.jpg)

# PianoView

A custom view on Android,which can help you easily to create a piano on Android.

## Features
- Beautiful UI.
- Good flexibility,which can use in different devices and layout.
- Mutil-Touch.
- Support piano sound.

## Gradle Dependency

Add it in your root `build.gradle` at the end of repositories:

```gradle
allprojects {
	repositories {
		...
		maven { url 'https://jitpack.io' }
	}
}
```
Add the dependency:

```gradle
dependencies {
        implementation 'com.github.ParadiseHell:PianoView:1.1.1'
}
```

## How to use

In the `XML` layout:

```xml
<com.chengtao.pianoview.view.PianoView
    android:id="@+id/pv"
    android:layout_width="match_parent"
    android:layout_height="match_parent"/>
```

### Independent key width (fit-to-width)

By default the keyboard renders keys at their intrinsic width and scrolls
horizontally (`intrinsic`). You can control key **width** independently of key
**height** so the full 88-key keyboard can span the view width while keeping a
tall, comfortable key height.

XML attributes (require `xmlns:app="http://schemas.android.com/apk/res-auto"`):

```xml
<com.chengtao.pianoview.view.PianoView
    android:id="@+id/pv"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:keyboardWidthMode="fitWidth"/>
```

`app:keyboardWidthMode` accepts:

| Value              | Behavior                                                              |
|--------------------|----------------------------------------------------------------------|
| `intrinsic`        | Default. Intrinsic key width, horizontally scrollable.               |
| `fitWidth`         | All 52 white keys span the full view width (no horizontal scrolling).|
| `fixedVisibleKeys` | Exactly `app:visibleWhiteKeys` white keys span the view width.       |
| `fixedKeyWidthDp`  | Every white key is exactly `app:whiteKeyWidthDp` wide (constant physical size on any device); the board scrolls, so wider screens show more keys. |

```xml
<com.chengtao.pianoview.view.PianoView
    ...
    app:keyboardWidthMode="fixedVisibleKeys"
    app:visibleWhiteKeys="14"/>
```

```xml
<com.chengtao.pianoview.view.PianoView
    ...
    app:keyboardWidthMode="fixedKeyWidthDp"
    app:whiteKeyWidthDp="48dp"/>
```

Programmatic API:

```java
pianoView.setKeyboardWidthMode(PianoView.WidthMode.FIT_WIDTH);
pianoView.getKeyboardWidthMode();
pianoView.setVisibleWhiteKeyCount(14);  // implies FIXED_VISIBLE_KEYS
pianoView.setWhiteKeyWidthDp(48f);      // implies FIXED_KEY_WIDTH_DP
```

In `fitWidth` mode `getPianoWidth() == getLayoutWidth()`, so `scroll(...)`
becomes a no-op. Height is still derived from the view height, so set any height
you like (tall keys) and all keys remain visible edge-to-edge.

To keep the **key size constant across devices** (so a wider screen shows *more*
keys instead of stretching them), use `fixedKeyWidthDp` with a key width in `dp`.
The full 88-key board keeps its size and scrolls horizontally.

In the sample app, long-press the music button to toggle between `intrinsic`
(scrollable) and `fitWidth` (all keys, full width, tall keys).

### Minimap overview (`PianoOverView`)

`PianoOverView` is a companion view that draws a miniature of the whole keyboard
with a highlighted rectangle marking the currently visible area. Drag (or tap) it
to scroll the piano; it also stays in sync when the piano is scrolled by other
means (SeekBar, arrows, auto-play).

```xml
<com.chengtao.pianoview.view.PianoOverView
    android:id="@+id/pov"
    android:layout_width="match_parent"
    android:layout_height="44dp"/>

<com.chengtao.pianoview.view.PianoView
    android:id="@+id/pv"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:keyboardWidthMode="fixedKeyWidthDp"
    app:whiteKeyWidthDp="48dp"/>
```

```java
pianoOverView.attachTo(pianoView);
```

Optional appearance attributes: `app:overviewBackgroundColor`,
`app:overviewWhiteKeyColor`, `app:overviewBlackKeyColor`,
`app:overviewHighlightColor`, `app:overviewHighlightBorderColor`.

To observe scrolling yourself, register a listener:

```java
pianoView.addOnPianoScrollListener((scrollX, pianoWidth, layoutWidth) -> { /* ... */ });
```

For more reference,plaese see the [sample](./sample).

## Document

- [English](https://github.com/ParadiseHell/PianoView/wiki)
- [中文](https://github.com/ParadiseHell/PianoView/wiki/首页)

## License

    Copyright 2016 ChengTao

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
