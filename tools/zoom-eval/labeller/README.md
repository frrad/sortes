# Crop labeller

A web page for judging the reader's crops page by page. Its labels are what the crop model is
scored and fitted against. The page is `index.html`, `app.js` and `style.css` here. It is served,
with the exported papers and a save endpoint, by `zoom-eval labeller-serve`.

## Starting a session

From the repo root, on the host:

    tools/zoom-eval/label.sh

It renders any paper in the labelling set that hasn't been rendered yet, then serves the
labeller on `http://127.0.0.1:8766/` until stopped with Ctrl-C. In bb, run it in a terminal of
the labelling thread:

    bb terminal create --title "sortes labeller :8766" --command "tools/zoom-eval/label.sh"

To open it from a laptop, expose the port from that thread and open the URL it prints:

    bb connect expose 8766

Labels are saved as you go, straight into `tools/zoom-eval/labels/`. Commit them (see
`labels/README.md`).

- `label.sh --render` re-renders every paper with the current code and weights. Use it after a
  model change, so the page shows the new crops; earlier labels are kept.
- `LABELLER_PORT` and `LABELLER_DATA` change the port and where renders go (default
  `~/sortes-review/labeller/data`).

## Adding a paper to the labelling set

1. If it isn't in `tools/zoom-eval/corpus.tsv`, add it there: arXiv id, file name, category,
   title, and for a non-arXiv PDF, its URL. An excerpt of a long PDF is
   `<arxiv-id>-p<first>-<last>`.
2. Add its id to `tools/zoom-eval/labeller/papers.txt`, where the order is the order of the tabs.
3. Restart `label.sh`. It fetches the PDF and renders just the new paper.

## Under the hood

`label.sh` runs two `zoom-eval` subcommands:

- `labeller-export --papers=id,id [--out=] [--aspect=] [--weights=] [--<weight>=]` renders each
  page in its padded screen frame, with the crops the reader chooses today (the reader's own
  `analyzePaper`, rendered with mutool). It writes `paper.json` and `pNNN.png`
  per paper, plus `papers.json` listing them.
- `labeller-serve --web= --labels= --data= [--host=127.0.0.1] [--port=8766]` serves:
  - the page at `/`;
  - renders under `/data/`;
  - labels at `GET` / `PUT /api/labels/<paper>`. A save is validated against the schema and
    written atomically, as canonical JSON.

The server runs from a copy of the installed distribution, outside Gradle, so it never holds the
build lock. The page itself (`index.html`, `app.js`, `style.css` here) is served live from the
repo, so edits to it show on a reload.

## Using it

Each page shows:
- the reader's crop, as a **solid frame** labelled with its window (W1, W2…);
- the ink it cuts off, in **red boxes**;
- **your window**, as a dashed frame. Drag it to where the window should be. A plain click
  doesn't move it.

Label as many or as few pages as you like, in any way: a verdict, tags, a comment or a moved
window. A page with any of these counts as reviewed; pages left alone count as no opinion. The
tabs at the top show each paper's reviewed count. **Done with this paper → next paper** (`N`)
moves on. There's no submit: "Saved ✓" confirms each autosave.

In the strip:
- ✓ good;
- ✗ bad;
- ✎ comment or tags;
- ⤧ window moved.

The slider sets the paper's zoom, which resizes every page's window: one crop size per paper
(AGENTS.md). The panel on the right previews the crop as the tablet would show it. It shows yours
once you've moved the window or set a zoom, and the model's otherwise.

Keys:

| key | action |
| --- | --- |
| `G` / `B` | good / bad |
| `1`–`7` | tags |
| `J` `→` / `K` `←` | next / previous page |
| `U` | next page not reviewed yet |
| `N` | done with this paper: next paper |
| `R` | window back to the model's position |
| `Shift`+arrows | nudge the window (with `Alt`, finer) |
| `C` | comment |
| `?` | help |

## Saving: nothing is lost

- **Autosave:** every change saves about half a second later, and on leaving the page (with a
  request that outlives it).
- **Backup in the browser:** until the server has a change, it is also kept in this browser's
  storage, one backup per tab. If the server is down, or the tab is closed or reloaded first, the
  next visit restores those changes and saves them (also for papers not reopened).
- **Failed saves:** the page shows a black **NOT SAVED** banner and retries by itself (after 1, 2,
  5, 10, then every 30 seconds, and whenever the browser comes back online).
- **Two tabs:** each save carries the revision of the labels the tab last saw. If another tab saved
  meanwhile, the server refuses with 409 and the current labels. The tab then puts its own unsaved
  changes on top and saves again, so neither tab overwrites the other's work.
- **The comment box:** it always belongs to the page shown. Changing page refreshes it even while
  it has focus, which on a Mac clicking a button doesn't take away.
- **No-op moves:** a window nudged off and back to the model's centre (within 0.5 pt) is no move,
  and isn't recorded.

Checks:
- `node --test tools/zoom-eval/labeller/labels.test.js` covers the label logic.
- `LabellerServerTest` covers revisions and 409.
- `labeller/checks/save-check.mjs` is a headless-Chromium run of every way of moving on:
  - Next, the filmstrip, reload, N to the next paper;
  - the server going down and coming back;
  - closed tabs while it was down;
  - two tabs;
  - nudges.

## Label schema (version 1)

One file per paper: `tools/zoom-eval/labels/<paper>.json`. It is canonical JSON (sorted keys,
two-space indent), so saving the same labels again leaves it byte for byte the same.

```json
{
  "schema": 1,
  "paper": "1706.03762",
  "labelledAgainst": {
    "commit": "5ba99cff70e9",
    "algorithm": "tuned+text",
    "weights": { "alpha": 1, "beta": 0.5, "epsilon": 0, "...": "..." },
    "scale": 0.815,
    "aspect": 0.75,
    "centres": [{ "x": 306.0, "y": 396.0 }, "..."],
    "parameters": { "textComfortablePx": 28, "...": "..." }
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
| `labelledAgainst` | The crops being judged: the commit (`-dirty` if the zoom code had uncommitted changes), the algorithm (the config's name), the model's weights, the scale it chose, the screen aspect (width / height), each page's window centre as shown (`centres`, page points; scoring uses these, so later model changes can't move what was judged) and any other model `parameters` (the text model's). |
| `idealScale` | The paper's ideal zoom, as the reader's `CropWindow.scale` (a fraction of the padded page; zoom = 1 / scale). `null` means the model's scale is fine. |
| `pages` | Keyed by page number, counting from 1. Pages nothing has been said about are left out. |
| `verdict` | `"good"`, `"bad"` or `null`. |
| `tags` | Any of `top-cut`, `bottom-cut`, `side-cut`, `too-loose`, `off-centre`, `window-jumped`, `other`. |
| `idealCentre` | Where the window's centre should be, in points from the page's top-left corner (it may lie in the padding around the page). The window's size is `idealScale`, or the model's scale if that is `null`. `null` means the model's position is fine. |

Labels are read and written by `PaperLabels` in
`tools/zoom-eval/src/main/kotlin/dev/sortes/zoomeval/labeller/Labels.kt`, which validates them.
