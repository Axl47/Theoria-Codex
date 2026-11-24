# 0135. For You vets pictures before dealing only while hidden tags could catch them, and never passes an unchecked post

Status: Accepted

## Context

Hidden tags hide posts by their provider tags, but sparsely tagged providers leave a post's picture uncaught. The opt-in picture tagger can tag the picture, but it loads a model and tags one post at a time, so For You must not spend that cost when nothing could be caught or let a post through while its check is pending.

## Decision

- Posts are vetted only while the tagger is ready and at least one hidden tag could catch what it sees (`vetsPictures`); hiding is all the tags are for here. A post waits for its picture only when its provider tagged it too sparsely for its own tags to settle it (`needsPictureCheck`, `needsLook`). While vetting runs, such a page's posts wait in `MixLane.unvetted` and join `buffer`, the posts the deal takes from, only after their pictures are tagged, so a post a hidden tag catches by its picture is never dealt. The rest join the buffer at once.
- `vet` tags pictures one at a time, from the running lane with the fewest vetted posts (ties by posts vetted so far, so lanes take turns), `VET_AHEAD` (3) posts ahead of the deal, or whatever its count once a deal came up short. A tagged post joins its buffer unless a hidden tag now catches it, and a deal that came up short deals again. `vet` does nothing while already running and every `refill` starts it again; the job is meant to be assigned to `vetting` before it starts, because on the immediate main dispatcher the loop runs in place and a deal it makes calls back into `vet` before it first suspends. The code creates the job lazily but starts it in `.also { it.start() }`, before the assignment completes, so such a re-entrant call can find no running job and start a second loop.
- A post whose preview did not load is left out, since its picture may carry a hidden tag. While the model will not load (`PictureLook.Blind`) vetting waits `BLIND_RETRY_MS` (5 s) and tries again rather than pass anything. Once vetting stops, what is left passes on what is known. A post the deal would pass over anyway is dropped untagged, so such posts neither cost a picture each nor keep their lane first in line.
- The first hidden tag turns vetting on, and what the lanes already hold is sent back to be vetted (`revet`); a tagger that becomes ready does the same. The model starts loading (`prepare`) while lanes are planned and their first pages fetched, so the first check need not wait. Deleting the tagger rebuilds the Mix (lanes planned with its tags would otherwise stay), and the opt-out check runs before the Mix check because a plan in flight may have read the tagger's evidence.
- Posts a like asks for are vetted like the lanes' posts: as many are tagged as it takes to find `INJECTED` candidates. Trails are not vetted; they leave out what is known to be hidden.
- Hiding checks the media filter and hidden tags by the tags and by what the tagger knows now of the picture (`shows`). Only the Library's stored predictions move as a flow into the level feeds; a card analysed this session was checked before it was dealt.

## Consequences

With no hidden tags or no tagger, For You pays nothing for vetting. With both, dealing waits on pictures, so a slow model slows the first cards.

## Applies to

- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouDeckViewModel.kt`: `vet`, `vetOne`, `vetsPictures`, `needsLook`, `shows`, `vetted`, `BLIND_RETRY_MS`, `VET_AHEAD`
- `feature-discover/src/main/kotlin/com/theoriacodex/app/recommend/ForYouMix.kt`: `MixLane.unvetted`, `MixLane.vetted`, `nextToVet`, `revet`, `passesOver`
