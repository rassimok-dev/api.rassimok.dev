package dev.rassimok.api.klad;

import java.util.List;

/** The rooms the lobby lists: the host's nick and a count of players, never other nicks, ids or emails. */
record RoomListView(List<ListedRoom> rooms) {

    record ListedRoom(String id, String state, RoomView.Host host, int players, int size, int level, String shape) {
    }
}
