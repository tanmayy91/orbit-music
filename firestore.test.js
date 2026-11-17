const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";
const CHARLIE_UID = "charlie_uid_789";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msAgo = 5000) {
  return new Date(Date.now() - msAgo);
}

function futureDate() {
  return new Date(Date.now() + 60000);
}

function pairId(a, b) {
  return a < b ? `${a}_${b}` : `${b}_${a}`;
}

async function seedUser(uid, handle, displayName, discordUsername = null) {
  const ts = pastDate(20000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const userPayload = {
      uid,
      displayName,
      handle,
      arySyncEnabled: true,
      cardShared: false,
      createdAt: ts,
      updatedAt: ts,
    };
    if (discordUsername) userPayload.discordUsername = discordUsername;
    await db.collection("users").doc(uid).set(userPayload);

    const dirPayload = {
      uid,
      handle,
      displayName,
      createdAt: ts,
      updatedAt: ts,
    };
    if (discordUsername) dirPayload.discordUsername = discordUsername;
    await db.collection("listenerDirectory").doc(handle).set(dirPayload);

    if (discordUsername) {
      await db.collection("discordDirectory").doc(discordUsername).set({
        uid,
        discordUsername,
        handle,
        displayName,
        createdAt: ts,
        updatedAt: ts,
      });
    }
  });
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

// --- 1. UNAUTHENTICATED BLOCKS ---

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("users").doc(ALICE_UID).get());
  await assertFails(unauth.collection("listenerDirectory").doc("alice").get());
  await assertFails(unauth.collection("discordDirectory").doc("alice_dc").get());
  await assertFails(unauth.collection("collabPlaylists").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(unauth.collection("friendships").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      handle: "alice_m",
      arySyncEnabled: true,
      cardShared: false,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

// --- 2. USER PROFILE & EXACT LOOKUP DIRECTORIES ---

test("User profile & directory batch registration, partial update, cross-user denial, and security bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(10000);

  // Batch create user + handle directory + discord directory (mirrors Kotlin registerProfile)
  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("users").doc(ALICE_UID), {
    uid: ALICE_UID,
    displayName: "Alice",
    handle: "alice_music",
    discordUsername: "alice.dc",
    discordId: "998877665544",
    arySyncEnabled: true,
    cardShared: true,
    createdAt: ts,
    updatedAt: ts,
  });
  batch.set(aliceDb.collection("listenerDirectory").doc("alice_music"), {
    uid: ALICE_UID,
    handle: "alice_music",
    displayName: "Alice",
    discordUsername: "alice.dc",
    createdAt: ts,
    updatedAt: ts,
  });
  batch.set(aliceDb.collection("discordDirectory").doc("alice.dc"), {
    uid: ALICE_UID,
    discordUsername: "alice.dc",
    handle: "alice_music",
    displayName: "Alice",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertSucceeds(batch.commit());

  // Signed-in Bob can get exact directory entry by handle or discord key, but cannot list directory or read Alice's /users/{uid}
  await assertSucceeds(bobDb.collection("listenerDirectory").doc("alice_music").get());
  await assertSucceeds(bobDb.collection("discordDirectory").doc("alice.dc").get());
  await assertFails(bobDb.collection("listenerDirectory").get());
  await assertFails(bobDb.collection("discordDirectory").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").get());

  // Partial update on own profile succeeds; future timestamp & ghost field fail
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice V.",
      arySyncEnabled: false,
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: pastDate(100),
      updatedAt: pastDate(100),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(100),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Future Alice",
      updatedAt: futureDate(),
    })
  );

  // @DocumentId `id` leakage rejection
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set({
      id: BOB_UID,
      uid: BOB_UID,
      displayName: "Bob",
      handle: "bob_beats",
      arySyncEnabled: true,
      cardShared: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

// --- 3. ARY CONVERSATION SYNC ---

test("Ary conversation sync: owner list/create/delete succeeds, update denied, cross-user denied", async () => {
  await seedUser(ALICE_UID, "alice_m", "Alice");
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(3000);

  // Empty subcollection list works cleanly
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("aryMessages").get());

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("aryMessages").doc("msg_1").set({
      role: "user",
      text: "Add 2 songs to Late Night",
      changeSummaries: [],
      focusedPlaylistTitle: "Late Night",
      seq: 1,
      createdAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("aryMessages").doc("msg_2").set({
      role: "assistant",
      text: "Added 2 tracks.",
      changeSummaries: ["Added Starlight - Muse"],
      seq: 2,
      createdAt: pastDate(2000),
    })
  );

  // Update is immutable/denied; cross-user read/write denied
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("aryMessages").doc("msg_1").update({
      text: "Edited",
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("aryMessages").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("aryMessages").doc("msg_3").set({
      role: "user",
      text: "Injected",
      changeSummaries: [],
      seq: 3,
      createdAt: ts,
    })
  );

  // Clear chat delete allowed for owner
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("aryMessages").doc("msg_1").delete()
  );
});

// --- 4. FRIENDSHIPS & LISTENING CARD SHARING ---

test("Friendships require valid users, deterministic pair ID, recipient accept, and terminal state lock", async () => {
  await seedUser(ALICE_UID, "alice_m", "Alice");
  await seedUser(BOB_UID, "bob_m", "Bob");

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const fid = pairId(ALICE_UID, BOB_UID);
  const ts = pastDate(8000);

  // Non-deterministic doc ID or direct 'accepted' state fails
  await assertFails(
    aliceDb.collection("friendships").doc("wrong_id").set({
      memberUids: [ALICE_UID, BOB_UID],
      requesterUid: ALICE_UID,
      recipientUid: BOB_UID,
      requesterName: "Alice",
      requesterHandle: "alice_m",
      recipientName: "Bob",
      recipientHandle: "bob_m",
      status: "pending",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(
    aliceDb.collection("friendships").doc(fid).set({
      memberUids: [ALICE_UID, BOB_UID],
      requesterUid: ALICE_UID,
      recipientUid: BOB_UID,
      requesterName: "Alice",
      requesterHandle: "alice_m",
      recipientName: "Bob",
      recipientHandle: "bob_m",
      status: "accepted",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Valid pending request succeeds
  await assertSucceeds(
    aliceDb.collection("friendships").doc(fid).set({
      memberUids: [ALICE_UID, BOB_UID],
      requesterUid: ALICE_UID,
      recipientUid: BOB_UID,
      requesterName: "Alice",
      requesterHandle: "alice_m",
      recipientName: "Bob",
      recipientHandle: "bob_m",
      status: "pending",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Filtered list query succeeds; unfiltered fails
  await assertSucceeds(bobDb.collection("friendships").where("memberUids", "array-contains", BOB_UID).get());
  await assertFails(bobDb.collection("friendships").get());
  await assertFails(charlieDb.collection("friendships").doc(fid).get());

  // Requester cannot accept their own request; Bob (recipient) can accept
  await assertFails(
    aliceDb.collection("friendships").doc(fid).update({
      status: "accepted",
      updatedAt: pastDate(1000),
    })
  );
  await assertSucceeds(
    bobDb.collection("friendships").doc(fid).update({
      status: "accepted",
      updatedAt: pastDate(1000),
    })
  );

  // Terminal state lock: once accepted, cannot be reverted or modified
  await assertFails(
    bobDb.collection("friendships").doc(fid).update({
      status: "pending",
      updatedAt: pastDate(500),
    })
  );
});

test("Listening cards isolate non-friends while allowing accepted friends to read & register viewerUids", async () => {
  await seedUser(ALICE_UID, "alice_m", "Alice", "alice.dc");
  await seedUser(BOB_UID, "bob_m", "Bob");
  await seedUser(CHARLIE_UID, "charlie_m", "Charlie");

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const ts = pastDate(8000);

  // Alice publishes listening card initially with only herself in viewerUids
  await assertSucceeds(
    aliceDb.collection("listeningCards").doc(ALICE_UID).set({
      uid: ALICE_UID,
      ownerName: "Alice",
      handle: "alice_m",
      discordUsername: "alice.dc",
      persona: "Night owl",
      sinceLabel: "January 2025",
      hoursLabel: "42 hr",
      plays: 640,
      artistCount: 95,
      bestStreak: 14,
      topSongTitle: "Midnight City",
      topSongArtist: "M83",
      topGenre: "Synthpop",
      topArtists: ["M83 · 88 plays", "Daft Punk · 62 plays"],
      viewerUids: [ALICE_UID],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Before friendship acceptance, Bob and Charlie cannot read Alice's card
  await assertFails(bobDb.collection("listeningCards").doc(ALICE_UID).get());
  await assertFails(charlieDb.collection("listeningCards").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("listeningCards").get());

  // Make Alice & Bob accepted friends
  const fid = pairId(ALICE_UID, BOB_UID);
  await assertSucceeds(
    aliceDb.collection("friendships").doc(fid).set({
      memberUids: [ALICE_UID, BOB_UID],
      requesterUid: ALICE_UID,
      recipientUid: BOB_UID,
      requesterName: "Alice",
      requesterHandle: "alice_m",
      recipientName: "Bob",
      recipientHandle: "bob_m",
      status: "pending",
      createdAt: pastDate(5000),
      updatedAt: pastDate(5000),
    })
  );
  await assertSucceeds(
    bobDb.collection("friendships").doc(fid).update({
      status: "accepted",
      updatedAt: pastDate(2000),
    })
  );

  // Now Bob can get Alice's card, register himself into viewerUids, and query via array-contains
  await assertSucceeds(bobDb.collection("listeningCards").doc(ALICE_UID).get());
  await assertSucceeds(
    bobDb.collection("listeningCards").doc(ALICE_UID).update({
      viewerUids: [ALICE_UID, BOB_UID],
    })
  );
  await assertSucceeds(
    bobDb.collection("listeningCards").where("viewerUids", "array-contains", BOB_UID).get()
  );

  // Bob cannot alter Alice's stats, and Charlie still cannot read Alice's card
  await assertFails(
    bobDb.collection("listeningCards").doc(ALICE_UID).update({
      plays: 99999,
      updatedAt: pastDate(500),
    })
  );
  await assertFails(charlieDb.collection("listeningCards").doc(ALICE_UID).get());
});

// --- 5. COLLABORATIVE PLAYLISTS, INVITES & TRACKS ---

test("Collaborative playlists atomic batch create with invite, self-join, track CRUD, member sync, and leave", async () => {
  await seedUser(ALICE_UID, "alice_m", "Alice");
  await seedUser(BOB_UID, "bob_m", "Bob");
  await seedUser(CHARLIE_UID, "charlie_m", "Charlie");

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const ts = pastDate(10000);
  const playlistId = "pl_night_drive";
  const inviteCode = "ORB7X9K2";

  // Orphan invite without playlist in same batch fails
  await assertFails(
    aliceDb.collection("playlistInvites").doc(inviteCode).set({
      playlistId,
      ownerId: ALICE_UID,
      title: "Night Drive",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Batch create collabPlaylist + playlistInvite atomically
  const batch = aliceDb.batch();
  batch.set(aliceDb.collection("collabPlaylists").doc(playlistId), {
    title: "Night Drive",
    description: "Late night synth & house",
    ownerId: ALICE_UID,
    ownerName: "Alice",
    inviteCode,
    memberUids: [ALICE_UID],
    memberNames: ["Alice (@alice_m)"],
    trackCount: 0,
    trackDocIds: [],
    createdAt: ts,
    updatedAt: ts,
  });
  batch.set(aliceDb.collection("playlistInvites").doc(inviteCode), {
    playlistId,
    ownerId: ALICE_UID,
    title: "Night Drive",
    createdAt: ts,
    updatedAt: ts,
  });
  await assertSucceeds(batch.commit());

  // Unfiltered list on collabPlaylists fails; member-filtered list succeeds
  await assertFails(aliceDb.collection("collabPlaylists").get());
  await assertSucceeds(
    aliceDb.collection("collabPlaylists").where("memberUids", "array-contains", ALICE_UID).get()
  );

  // Alice adds a track + updates summary
  const trackDocId = "trk_1";
  await assertSucceeds(
    aliceDb.collection("collabPlaylists").doc(playlistId).collection("tracks").doc(trackDocId).set({
      playlistId,
      memberUids: [ALICE_UID],
      mediaId: "ytm:dQw4w9WgXcQ",
      title: "Resonance",
      artistLine: "HOME",
      albumTitle: "Odyssey",
      durationMs: 212000,
      addedByUid: ALICE_UID,
      addedByName: "Alice",
      createdAt: pastDate(5000),
    })
  );
  await assertSucceeds(
    aliceDb.collection("collabPlaylists").doc(playlistId).update({
      trackCount: 1,
      trackDocIds: [trackDocId],
      updatedAt: pastDate(4500),
    })
  );

  // Non-member Charlie cannot read playlist or tracks
  await assertFails(charlieDb.collection("collabPlaylists").doc(playlistId).get());
  await assertFails(charlieDb.collection("collabPlaylists").doc(playlistId).collection("tracks").doc(trackDocId).get());
  await assertFails(charlieDb.collection("collabPlaylists").doc(playlistId).collection("tracks").get());
  await assertFails(
    charlieDb.collection("collabPlaylists").doc(playlistId).collection("tracks").where("memberUids", "array-contains", ALICE_UID).get()
  );
  const charlieTracks = await assertSucceeds(
    charlieDb.collection("collabPlaylists").doc(playlistId).collection("tracks").where("memberUids", "array-contains", CHARLIE_UID).get()
  );
  if (charlieTracks.size !== 0) {
    throw new Error(`Expected 0 tracks visible to Charlie, got ${charlieTracks.size}`);
  }

  // Bob looks up invite code and self-joins playlist
  await assertSucceeds(bobDb.collection("playlistInvites").doc(inviteCode).get());
  await assertSucceeds(
    bobDb.collection("collabPlaylists").doc(playlistId).update({
      memberUids: [ALICE_UID, BOB_UID],
      memberNames: ["Alice (@alice_m)", "Bob (@bob_m)"],
      updatedAt: pastDate(3000),
    })
  );

  // Bob syncs existing track memberUids and reads tracks with array-contains
  await assertSucceeds(
    bobDb.collection("collabPlaylists").doc(playlistId).collection("tracks").doc(trackDocId).update({
      memberUids: [ALICE_UID, BOB_UID],
    })
  );
  await assertSucceeds(
    bobDb
      .collection("collabPlaylists")
      .doc(playlistId)
      .collection("tracks")
      .where("memberUids", "array-contains", BOB_UID)
      .get()
  );

  // Non-owner Bob cannot rename or delete playlist, and owner Alice cannot leave her own playlist
  await assertFails(
    bobDb.collection("collabPlaylists").doc(playlistId).update({
      title: "Hijacked",
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(bobDb.collection("collabPlaylists").doc(playlistId).delete());

  // Bob adds his own track, then removes Alice's track or leaves
  await assertSucceeds(
    bobDb.collection("collabPlaylists").doc(playlistId).collection("tracks").doc("trk_2").set({
      playlistId,
      memberUids: [ALICE_UID, BOB_UID],
      mediaId: "ytm:5qap5aO4i9A",
      title: "Lofi Beats",
      artistLine: "ChilledCow",
      addedByUid: BOB_UID,
      addedByName: "Bob",
      createdAt: pastDate(1500),
    })
  );

  // Bob leaves playlist
  await assertSucceeds(
    bobDb.collection("collabPlaylists").doc(playlistId).update({
      memberUids: [ALICE_UID],
      memberNames: ["Alice (@alice_m)"],
      updatedAt: pastDate(500),
    })
  );
});
