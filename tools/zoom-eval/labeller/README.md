# Crop labeller

A web page for judging the reader's crops page by page. Its labels are what the crop model is
scored and fitted against. The page is `index.html`, `app.js` and `style.css` here. It is served,
with the exported papers and a save endpoint, by `zoom-eval labeller-serve`.

## Running

Everything runs on the host; nothing touches the tablet.

1. **Export** the papers to label. This renders each page in its padded screen frame and records
   the crops the reader chooses today (zoom-eval's `reader` algorithm: the app's `zoom/` code,
   with mutool for rendering):

       flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:run --args="labeller-export \
           --papers=1706.03762,2311.08105,1502.03167,2006.11239,2106.10165-p1-40,shannon1948"

   The ids come from `corpus.tsv`. The output goes to `~/sortes-review/labeller/data/` (use
   `--out=` to change it), with one folder per paper plus `papers.json` listing them. The usual
   `--aspect=`, `--weights=` and `--<weight>=` options apply.

2. **Serve.** Build the server once, then run it outside Gradle, so it doesn't hold the build
   lock:

       flock /tmp/sortes-gradle.lock ./gradlew :zoom-eval:installDist
       cp -r tools/zoom-eval/build/install/zoom-eval /tmp/sortes-labeller
       JAVA_OPTS=-Xmx128m /tmp/sortes-labeller/bin/zoom-eval labeller-serve \
           --web=$PWD/tools/zoom-eval/labeller --labels=$PWD/tools/zoom-eval/labels

   It listens on `127.0.0.1:8766` (`--host=`, `--port=`), and serves:
   - the page at `/`;
   - exports under `/data/`;
   - labels at `GET` / `PUT /api/labels/<paper>`. A save is validated against the schema and
     written atomically, as canonical JSON.

## Using it

Each page shows:
- the reader's crop, as a **solid frame** labelled with its window (W1, W2…);
- the ink it cuts off, in **red boxes**;
- **your window**, as a dashed frame. Drag it, or click where its centre should go.

The slider sets the paper's zoom, which resizes every page's window: one crop size per paper
(AGENTS.md). The panel on the right previews the crop as the tablet would show it. It shows yours
once you've moved the window or set a zoom, and the model's otherwise.

Keys:

| key | action |
| --- | --- |
| `G` / `B` | good / bad |
| `1`–`7` | tags |
| `J` `→` / `K` `←` | next / previous page |
| `U` | next page without a verdict |
| `R` | window back to the model's position |
| `Shift`+arrows | nudge the window (with `Alt`, finer) |
| `C` | comment |
| `?` | help |

Everything saves as you go.

## Label schema (version 1)

One file per paper: `tools/zoom-eval/labels/<paper>.json`. It is canonical JSON (sorted keys,
two-space indent), so saving the same labels again leaves it byte for byte the same.

```json
{
  "schema": 1,
  "paper": "1706.03762",
  "labelledAgainst": {
    "commit": "5ba99cff70e9",
    "algorithm": "reader",
    "weights": { "alpha": 1, "beta": 0.1, "epsilon": 300, "...": "..." },
    "scale": 0.815,
    "aspect": 0.75
  },
  "idealScale": 0.8,
  "comment": "Paper-level comment",
  "pages": {
    "1": {
      "verdict": "bad",
      "tags": ["top-cut"],
      "comment": "Cuts the permission notice",
      "idealCentre": { "x": 306.0, "y": 410.5 }
    }
  }
}
```

| field | meaning |
| --- | --- |
| `labelledAgainst` | The crops being judged: the commit (`-dirty` if the zoom code had uncommitted changes), the algorithm, the model's weights, the scale it chose, and the screen aspect (width / height). |
| `idealScale` | The paper's ideal zoom, as the reader's `CropWindow.scale` (a fraction of the padded page; zoom = 1 / scale). `null` means the model's scale is fine. |
| `pages` | Keyed by page number, counting from 1. Pages nothing has been said about are left out. |
| `verdict` | `"good"`, `"bad"` or `null`. |
| `tags` | Any of `top-cut`, `bottom-cut`, `side-cut`, `too-loose`, `off-centre`, `window-jumped`, `other`. |
| `idealCentre` | Where the window's centre should be, in points from the page's top-left corner (it may lie in the padding around the page). The window's size is `idealScale`, or the model's scale if that is `null`. `null` means the model's position is fine. |

Labels are read and written by `PaperLabels` in
`tools/zoom-eval/src/main/kotlin/dev/sortes/zoomeval/labeller/Labels.kt`, which validates them.
