/* Daddy和应帆的共读书房 v1.1.1 */
var BOOK = "book_";
var CHUNK = "chunk_";
var NOTE = "note_";
var BOOKMARK = "bookmark_";
var CURRENT = "current_book";

function clean(v) {
  if (v === null || v === undefined) return "";
  return String(v).replace(/^\s+|\s+$/g, "");
}

function norm(v) {
  return clean(v).toLowerCase();
}

function now() {
  return new Date().toISOString();
}

function makeId() {
  return String(new Date().getTime()) + "_" + String(Math.floor(Math.random() * 1000000));
}

function json(key, fallback) {
  var raw = dataStore.get(key);
  if (!raw) return fallback;
  try {
    return JSON.parse(raw);
  } catch (e) {
    return fallback;
  }
}

function save(key, value) {
  dataStore.set(key, JSON.stringify(value));
}

function progressPercent(book) {
  var page = Number(book.progress_page || 1);
  var total = Number(book.total_pages || 1);
  if (page <= 1 || total <= 1) return 0;
  return Math.min(100, Math.round((page - 1) * 100 / (total - 1) * 10) / 10);
}

function books() {
  var keys = dataStore.list(BOOK);
  var result = [];
  var i;
  var item;
  for (i = 0; i < keys.length; i++) {
    item = json(keys[i], null);
    if (item && item.id) result.push(item);
  }
  result.sort(function (a, b) {
    return a.added_at > b.added_at ? -1 : 1;
  });
  return result;
}

function pub(book) {
  return {
    id: book.id,
    title: book.title,
    author: book.author || "",
    total_pages: book.total_pages,
    progress_page: book.progress_page || 1,
    progress_percent: progressPercent(book),
    added_at: book.added_at,
    updated_at: book.updated_at || book.added_at
  };
}

function resolve(ref) {
  var query = norm(ref || dataStore.get(CURRENT));
  var all = books();
  var matches = [];
  var i;
  if (!query) return { success: false, error: "书房里还没有选中的书" };
  for (i = 0; i < all.length; i++) {
    if (norm(all[i].id) === query) matches = [all[i]];
  }
  if (!matches.length) {
    for (i = 0; i < all.length; i++) {
      if (norm(all[i].title).indexOf(query) >= 0) matches.push(all[i]);
    }
  }
  if (!matches.length) return { success: false, error: "没有找到这本书" };
  if (matches.length > 1) {
    var candidates = [];
    for (i = 0; i < matches.length; i++) candidates.push(pub(matches[i]));
    return { success: false, ambiguous: true, error: "找到多本匹配书籍", candidates: candidates };
  }
  return { success: true, book: matches[0] };
}

function notes(bookId, page) {
  var keys = dataStore.list(NOTE + bookId + "_");
  var result = [];
  var i;
  var item;
  for (i = 0; i < keys.length; i++) {
    item = json(keys[i], null);
    if (item && (!page || item.page === page)) result.push(item);
  }
  result.sort(function (a, b) {
    return a.created_at < b.created_at ? -1 : 1;
  });
  return result;
}

function bookmarks(bookId) {
  var keys = dataStore.list(BOOKMARK + bookId + "_");
  var result = [];
  var i;
  var item;
  for (i = 0; i < keys.length; i++) {
    item = json(keys[i], null);
    if (item) result.push(item);
  }
  result.sort(function (a, b) {
    if (a.page !== b.page) return a.page - b.page;
    return a.created_at < b.created_at ? -1 : 1;
  });
  return result;
}

function pageData(book, page) {
  if (page < 1 || page > book.total_pages) {
    return { success: false, error: "页码超出范围", total_pages: book.total_pages };
  }
  return {
    success: true,
    book: pub(book),
    page: page,
    text: dataStore.get(CHUNK + book.id + "_" + page) || "",
    notes: notes(book.id, page),
    bookmarks: bookmarks(book.id),
    spoiler_boundary: book.progress_page || 1
  };
}

function list_books() {
  var all = books();
  var result = [];
  var i;
  for (i = 0; i < all.length; i++) result.push(pub(all[i]));
  return {
    success: true,
    count: result.length,
    current_book_id: dataStore.get(CURRENT),
    books: result
  };
}

function get_reading_context(params) {
  params = params || {};
  var found = resolve(params.book_ref);
  if (!found.success) return found;
  dataStore.set(CURRENT, found.book.id);
  return pageData(found.book, found.book.progress_page || 1);
}

function read_book_page(params) {
  params = params || {};
  var found = resolve(params.book_ref);
  var page;
  if (!found.success) return found;
  page = Number(params.page) || 1;
  if (page > (found.book.progress_page || 1) && params.allow_spoiler !== true) {
    return {
      success: false,
      spoiler_blocked: true,
      error: "这一页超过当前共同进度；需要应帆明确允许剧透后才能读取",
      current_progress: found.book.progress_page || 1
    };
  }
  return pageData(found.book, page);
}

function get_reading_toc(params) {
  params = params || {};
  var found = resolve(params.book_ref);
  var limit;
  var entries = [];
  var page;
  var text;
  var lines;
  var i;
  var line;
  var match;
  if (!found.success) return found;
  limit = params.include_future === true ? found.book.total_pages : (found.book.progress_page || 1);
  for (page = 1; page <= limit; page++) {
    text = dataStore.get(CHUNK + found.book.id + "_" + page) || "";
    lines = text.split(/\r?\n/);
    for (i = 0; i < lines.length && i < 12; i++) {
      line = clean(lines[i]);
      match = line.match(/^(第[0-9一二三四五六七八九十百千零〇两]+[章节卷回部篇].{0,34}|chapter\s+[0-9ivxlcdm]+.{0,34})$/i);
      if (match) {
        entries.push({ page: page, title: line });
        break;
      }
    }
  }
  return {
    success: true,
    book: pub(found.book),
    visible_page_limit: limit,
    entries: entries
  };
}

function update_reading_progress(params) {
  params = params || {};
  var found = resolve(params.book_ref);
  var page;
  if (!found.success) return found;
  page = Math.round(Number(params.page) || 0);
  if (page < 1 || page > found.book.total_pages) {
    return { success: false, error: "页码超出范围" };
  }
  found.book.progress_page = page;
  found.book.updated_at = now();
  save(BOOK + found.book.id, found.book);
  dataStore.set(CURRENT, found.book.id);
  return { success: true, message: "共读进度已更新", book: pub(found.book) };
}

function add_reading_note(params) {
  params = params || {};
  var found = resolve(params.book_ref);
  var author = norm(params.author);
  var content = clean(params.content);
  var page = Math.round(Number(params.page) || 0);
  var note;
  if (!found.success) return found;
  if (author !== "user" && author !== "ai") return { success: false, error: "author必须是user或ai" };
  if (!content) return { success: false, error: "批注不能为空" };
  if (page < 1 || page > found.book.total_pages) return { success: false, error: "页码超出范围" };
  note = {
    id: makeId(),
    book_id: found.book.id,
    page: page,
    author: author,
    quote: clean(params.quote),
    content: content,
    created_at: now()
  };
  save(NOTE + found.book.id + "_" + note.id, note);
  return { success: true, message: "共读批注已保存", note: note };
}

function list_reading_bookmarks(params) {
  params = params || {};
  var result = [];
  var found;
  var all;
  var i;
  var j;
  var list;
  if (params.book_ref) {
    found = resolve(params.book_ref);
    if (!found.success) return found;
    return { success: true, book: pub(found.book), bookmarks: bookmarks(found.book.id) };
  }
  all = books();
  for (i = 0; i < all.length; i++) {
    list = bookmarks(all[i].id);
    for (j = 0; j < list.length; j++) {
      list[j].book_title = all[i].title;
      result.push(list[j]);
    }
  }
  return { success: true, bookmarks: result };
}

function add_reading_bookmark(params) {
  params = params || {};
  var found = resolve(params.book_ref);
  var page = Math.round(Number(params.page) || 0);
  var list;
  var i;
  var bookmark;
  if (!found.success) return found;
  if (page < 1 || page > found.book.total_pages) return { success: false, error: "页码超出范围" };
  list = bookmarks(found.book.id);
  for (i = 0; i < list.length; i++) {
    if (list[i].page === page) {
      return { success: true, already_exists: true, message: "这一页已经有书签", bookmark: list[i] };
    }
  }
  bookmark = {
    id: makeId(),
    book_id: found.book.id,
    page: page,
    label: clean(params.label) || "第 " + page + " 页",
    created_at: now()
  };
  save(BOOKMARK + found.book.id + "_" + bookmark.id, bookmark);
  return { success: true, message: "书签已添加", bookmark: bookmark };
}

function delete_reading_bookmark(params) {
  params = params || {};
  var wanted = clean(params.bookmark_id);
  var keys;
  var i;
  var item;
  if (!wanted) return { success: false, error: "缺少书签ID" };
  keys = dataStore.list(BOOKMARK);
  for (i = 0; i < keys.length; i++) {
    item = json(keys[i], null);
    if (item && item.id === wanted) {
      dataStore.del(keys[i]);
      return { success: true, message: "书签已删除", bookmark: item };
    }
  }
  return { success: false, error: "没有找到这枚书签" };
}

function search_book(params) {
  params = params || {};
  var found = resolve(params.book_ref);
  var query = norm(params.query);
  var max;
  var result = [];
  var page;
  var text;
  var at;
  if (!found.success) return found;
  if (!query) return { success: false, error: "请输入关键词" };
  max = found.book.progress_page || 1;
  for (page = 1; page <= max && result.length < 20; page++) {
    text = dataStore.get(CHUNK + found.book.id + "_" + page) || "";
    at = norm(text).indexOf(query);
    if (at >= 0) {
      result.push({
        page: page,
        snippet: text.slice(Math.max(0, at - 80), Math.min(text.length, at + query.length + 140))
      });
    }
  }
  return { success: true, count: result.length, progress_limit: max, results: result };
}

function delete_book(params) {
  params = params || {};
  var found = resolve(params.book_ref);
  var chunkKeys;
  var noteKeys;
  var bookmarkKeys;
  var i;
  if (!found.success) return found;
  chunkKeys = dataStore.list(CHUNK + found.book.id + "_");
  noteKeys = dataStore.list(NOTE + found.book.id + "_");
  bookmarkKeys = dataStore.list(BOOKMARK + found.book.id + "_");
  for (i = 0; i < chunkKeys.length; i++) dataStore.del(chunkKeys[i]);
  for (i = 0; i < noteKeys.length; i++) dataStore.del(noteKeys[i]);
  for (i = 0; i < bookmarkKeys.length; i++) dataStore.del(bookmarkKeys[i]);
  dataStore.del(BOOK + found.book.id);
  if (dataStore.get(CURRENT) === found.book.id) dataStore.del(CURRENT);
  return { success: true, message: "书籍、共读记录和书签已删除", deleted: pub(found.book) };
}

exports.list_books = list_books;
exports.get_reading_context = get_reading_context;
exports.read_book_page = read_book_page;
exports.get_reading_toc = get_reading_toc;
exports.update_reading_progress = update_reading_progress;
exports.add_reading_note = add_reading_note;
exports.list_reading_bookmarks = list_reading_bookmarks;
exports.add_reading_bookmark = add_reading_bookmark;
exports.delete_reading_bookmark = delete_reading_bookmark;
exports.search_book = search_book;
exports.delete_book = delete_book;
