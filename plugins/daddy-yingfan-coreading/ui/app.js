(function () {
  "use strict";

  var state = {
    books: [],
    book: null,
    page: 1,
    view: "library",
    settings: {
      theme: "paper",
      font: "serif",
      fontSize: 20,
      lineHeight: 2,
      brightness: 0
    }
  };
  var el = {};
  var toastTimer;
  var SETTINGS_KEY = "reader_preferences_v1";
  var themes = [
    { value: "paper", label: "日间" },
    { value: "night", label: "夜间" },
    { value: "sepia", label: "暖黄" },
    { value: "blush", label: "粉雾" }
  ];
  var fonts = [
    { value: "serif", label: "书卷" },
    { value: "sans", label: "清晰" },
    { value: "round", label: "圆润" },
    { value: "mono", label: "等宽" }
  ];

  function id(name) { return document.getElementById(name); }
  function esc(value) {
    return String(value === null || value === undefined ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }
  function toast(message) {
    el.toast.textContent = message;
    el.toast.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.toast.classList.remove("show"); }, 2700);
  }
  function bridge() {
    return new Promise(function (resolve, reject) {
      var count = 0;
      var timer = setInterval(function () {
        count++;
        if (window.Bridge) {
          clearInterval(timer);
          resolve(window.Bridge);
        } else if (count > 100) {
          clearInterval(timer);
          reject(new Error("插件桥接失败"));
        }
      }, 50);
    });
  }
  function parse(value) {
    if (value === null || typeof value === "object") return value;
    try {
      var parsed = JSON.parse(value);
      return typeof parsed === "string" ? JSON.parse(parsed) : parsed;
    } catch (error) {
      return value;
    }
  }
  function tool(name, params) {
    return bridge().then(function (instance) {
      return instance.callTool(name, JSON.stringify(params || {}));
    }).then(function (result) {
      result = parse(result);
      if (result && result.result) result = parse(result.result);
      if (Array.isArray(result)) result = parse(result[0]);
      return result || {};
    });
  }
  function getData(key) {
    return bridge().then(function (instance) {
      if (instance.getData) return instance.getData(key);
      return instance.dataStoreGet(key);
    });
  }
  function setData(key, value) {
    return bridge().then(function (instance) {
      if (instance.setData) return instance.setData(key, value);
      return instance.dataStoreSet(key, value);
    });
  }
  function percentText(book) {
    var percent = Number(book.progress_percent || 0);
    if (percent <= 0) return "未读";
    if (percent >= 100) return "已读完";
    return "已读 " + percent + "%";
  }
  function coverKind(book) {
    var title = String(book.title || "");
    return /\.md$/i.test(title) ? "- MD -" : "- TXT -";
  }
  function sortBooks() {
    state.books.sort(function (a, b) {
      return String(a.updated_at || a.added_at) < String(b.updated_at || b.added_at) ? 1 : -1;
    });
  }
  function loadPreferences() {
    return getData(SETTINGS_KEY).then(function (value) {
      if (!value) return;
      try {
        var saved = parse(value);
        if (saved && typeof saved === "object") {
          Object.keys(state.settings).forEach(function (key) {
            if (saved[key] !== undefined) state.settings[key] = saved[key];
          });
        }
      } catch (error) {}
    }).catch(function () {});
  }
  function savePreferences() {
    return setData(SETTINGS_KEY, JSON.stringify(state.settings)).catch(function () {});
  }
  function load() {
    return tool("list_books", {}).then(function (result) {
      state.books = result.books || [];
      sortBooks();
      renderLibrary();
    });
  }
  function renderLibrary() {
    var isBookmarks = state.view === "bookmarks";
    el.library.hidden = isBookmarks;
    el.empty.hidden = isBookmarks || state.books.length !== 0;
    el.bookmarkPanel.hidden = !isBookmarks;
    el.importButton.hidden = isBookmarks;
    Array.prototype.forEach.call(document.querySelectorAll(".nav-item"), function (button) {
      button.classList.toggle("is-active", button.getAttribute("data-view") === state.view);
    });
    if (isBookmarks) {
      renderBookmarks();
      return;
    }
    el.library.innerHTML = state.books.map(function (book) {
      var percent = Math.max(0, Math.min(100, Number(book.progress_percent || 0)));
      return '<article class="book" data-id="' + esc(book.id) + '">' +
        '<button class="book-open" data-open aria-label="阅读 ' + esc(book.title) + '">' +
          '<span class="book-cover" data-kind="' + esc(coverKind(book)) + '"><span class="cover-title">' + esc(book.title) + '</span></span>' +
          '<span class="book-name">' + esc(book.title) + '</span>' +
          '<span class="book-meta">' + esc(percentText(book)) + '</span>' +
          '<span class="mini-progress"><i style="width:' + percent + '%"></i></span>' +
        '</button>' +
      '</article>';
    }).join("");
  }
  function renderBookmarks() {
    tool("list_reading_bookmarks", {}).then(function (result) {
      var bookmarks = result.bookmarks || [];
      if (!bookmarks.length) {
        el.bookmarkPanel.innerHTML = '<div class="empty-state"><span>🔖</span><b>还没有书签</b><p>读到想停下来的地方，就点阅读页底部的小书签。</p></div>';
        return;
      }
      el.bookmarkPanel.innerHTML = '<h2 class="bookmark-heading">书签</h2>' + bookmarks.map(function (bookmark) {
        return '<button class="bookmark-card" data-bookmark-book="' + esc(bookmark.book_id) + '" data-bookmark-page="' + Number(bookmark.page) + '">' +
          '<span class="bookmark-page">' + Number(bookmark.page) + '</span><span><b>' + esc(bookmark.label || "第 " + bookmark.page + " 页") + '</b><small>' + esc(bookmark.book_title || "共读书房") + '</small></span>' +
        '</button>';
      }).join("");
    }).catch(function () {
      el.bookmarkPanel.innerHTML = '<p class="toc-empty">书签读取失败，稍后再试试。</p>';
    });
  }
  function splitText(text) {
    var paragraphs;
    var pages = [];
    var buffer = "";
    text = String(text || "").replace(/\r/g, "").replace(/\n{4,}/g, "\n\n").trim();
    paragraphs = text.split(/\n{2,}/);
    paragraphs.forEach(function (paragraph) {
      paragraph = paragraph.trim();
      if (!paragraph) return;
      if (buffer && buffer.length + paragraph.length + 2 > 2200) {
        pages.push(buffer);
        buffer = "";
      }
      while (paragraph.length > 2400) {
        if (buffer) {
          pages.push(buffer);
          buffer = "";
        }
        pages.push(paragraph.slice(0, 2200));
        paragraph = paragraph.slice(2200);
      }
      buffer += (buffer ? "\n\n" : "") + paragraph;
    });
    if (buffer) pages.push(buffer);
    return pages;
  }
  function uid() { return Date.now() + "_" + Math.floor(Math.random() * 1000000); }
  function importBook() {
    bridge().then(function (instance) {
      return instance.pickFile();
    }).then(function (result) {
      if (!result || result.success === false) {
        if (result && result.error !== "User cancelled") toast(result.error || "导入失败");
        return null;
      }
      var title = prompt("书名", String(result.fileName || "未命名").replace(/\.[^.]+$/, ""));
      if (title === null || !title.trim()) return null;
      var author = prompt("作者（可以留空）", "");
      if (author === null) return null;
      var pages = splitText(result.content);
      if (!pages.length) throw new Error("文件里没有可读取的文字");
      return { title: title.trim(), author: author.trim(), pages: pages };
    }).then(function (source) {
      if (!source) return null;
      var bookId = uid();
      var book = {
        id: bookId,
        title: source.title,
        author: source.author,
        total_pages: source.pages.length,
        progress_page: 1,
        added_at: new Date().toISOString(),
        updated_at: new Date().toISOString()
      };
      var chain = Promise.resolve();
      return bridge().then(function (instance) {
        source.pages.forEach(function (pageText, index) {
          chain = chain.then(function () { return setData("chunk_" + bookId + "_" + (index + 1), pageText); });
        });
        return chain.then(function () { return setData("book_" + bookId, JSON.stringify(book)); })
          .then(function () { return setData("current_book", bookId); })
          .then(function () { return book; });
      });
    }).then(function (book) {
      if (!book) return;
      toast("《" + book.title + "》已放进我们的书架");
      return load();
    }).catch(function (error) {
      console.error(error);
      toast(error.message || "导入失败");
    });
  }
  function openBook(book, page) {
    state.book = book;
    state.page = Math.max(1, Math.min(Number(page || book.progress_page || 1), Number(book.total_pages)));
    el.reader.hidden = false;
    document.body.style.overflow = "hidden";
    applySettings();
    showPage();
  }
  function refreshBook(book) {
    var index = state.books.findIndex(function (item) { return item.id === book.id; });
    if (index >= 0) state.books[index] = book;
    state.book = book;
  }
  function showPage() {
    tool("read_book_page", {
      book_ref: state.book.id,
      page: state.page,
      allow_spoiler: state.page <= (state.book.progress_page || 1)
    }).then(function (result) {
      if (result.success === false && result.spoiler_blocked) {
        return tool("update_reading_progress", { book_ref: state.book.id, page: state.page })
          .then(function () { return tool("read_book_page", { book_ref: state.book.id, page: state.page, allow_spoiler: true }); });
      }
      return result;
    }).then(function (result) {
      if (result.success === false) throw new Error(result.error);
      refreshBook(result.book);
      el.bookTitle.textContent = result.book.title;
      el.pageInfo.textContent = "第 " + result.page + " / " + result.book.total_pages + " 页";
      el.readingProgress.style.width = Math.max(0, Math.min(100, Number(result.book.progress_percent || 0))) + "%";
      el.text.textContent = result.text;
      renderNotes(result.notes || []);
      renderChapterHint(result.text);
      el.prev.disabled = state.page <= 1;
      el.next.disabled = state.page >= result.book.total_pages;
      updateBookmarkButton(result.bookmarks || []);
    }).catch(function (error) {
      toast(error.message || "读取失败");
    });
  }
  function renderNotes(notes) {
    if (!notes.length) {
      el.notes.innerHTML = '<p class="no-notes">这一页还没有留下一句话。</p>';
      return;
    }
    el.notes.innerHTML = notes.map(function (note) {
      return '<article class="note"><b>' + (note.author === "ai" ? "Daddy" : "应帆") + '</b>' +
        (note.quote ? '<p class="note-quote">「' + esc(note.quote) + '」</p>' : "") +
        '<p>' + esc(note.content) + '</p></article>';
    }).join("");
  }
  function renderChapterHint(text) {
    var line = String(text || "").split(/\r?\n/).slice(0, 10).map(function (item) { return item.trim(); })
      .find(function (item) { return /^第[0-9一二三四五六七八九十百千零〇两]+[章节卷回部篇]|^chapter\s+/i.test(item); });
    el.chapterHint.textContent = line || "轻触书名可查看已识别章节";
  }
  function updateBookmarkButton(bookmarks) {
    var exists = bookmarks.some(function (bookmark) { return Number(bookmark.page) === Number(state.page); });
    el.bookmarkButton.textContent = exists ? "🔖" : "🔖";
    el.bookmarkButton.classList.toggle("is-saved", exists);
    el.bookmarkButton.title = exists ? "这一页已添加书签" : "添加书签";
  }
  function move(delta) {
    var next = state.page + delta;
    if (next < 1 || next > state.book.total_pages) return;
    state.page = next;
    if (next > state.book.progress_page) {
      state.book.progress_page = next;
      tool("update_reading_progress", { book_ref: state.book.id, page: next }).then(load);
    }
    showPage();
  }
  function addNote() {
    var quote = prompt("想引用的句子（可以留空）", "");
    if (quote === null) return;
    var content = prompt("写下应帆的感想", "");
    if (content === null || !content.trim()) return;
    tool("add_reading_note", {
      book_ref: state.book.id,
      page: state.page,
      author: "user",
      quote: quote,
      content: content.trim()
    }).then(function (result) {
      if (result.success === false) throw new Error(result.error);
      toast("批注已留在这一页");
      showPage();
    }).catch(function (error) { toast(error.message || "保存失败"); });
  }
  function addBookmark() {
    tool("add_reading_bookmark", { book_ref: state.book.id, page: state.page }).then(function (result) {
      if (result.success === false) throw new Error(result.error);
      toast(result.already_exists ? "这一页已经夹好书签了" : "书签已夹在这一页");
      showPage();
    }).catch(function (error) { toast(error.message || "书签保存失败"); });
  }
  function openToc() {
    el.tocSheet.hidden = false;
    el.tocList.innerHTML = '<p class="toc-empty">正在整理已读章节……</p>';
    tool("get_reading_toc", { book_ref: state.book.id, include_future: true }).then(function (result) {
      var entries = result.entries || [];
      if (!entries.length) {
        el.tocList.innerHTML = '<p class="toc-empty">这本书暂时没有识别到章节标题。你仍可用左右翻页继续阅读。</p>';
        return;
      }
      el.tocList.innerHTML = entries.map(function (entry) {
        return '<button class="toc-item" data-toc-page="' + Number(entry.page) + '"><span>' + esc(entry.title) + '</span><small>第 ' + Number(entry.page) + ' 页</small></button>';
      }).join("");
    }).catch(function () {
      el.tocList.innerHTML = '<p class="toc-empty">目录读取失败，稍后再试试。</p>';
    });
  }
  function closeSheets() {
    el.tocSheet.hidden = true;
    el.settingsSheet.hidden = true;
  }
  function renderChoices() {
    el.themeChoices.innerHTML = themes.map(function (item) {
      return '<button class="choice theme-choice" data-setting="theme" data-value="' + item.value + '"><i></i>' + item.label + '</button>';
    }).join("");
    el.fontChoices.innerHTML = fonts.map(function (item) {
      return '<button class="choice" data-setting="font" data-value="' + item.value + '">' + item.label + '</button>';
    }).join("");
  }
  function applySettings() {
    var root = el.reader;
    root.className = "reader-layer theme-" + state.settings.theme + " font-" + state.settings.font;
    el.text.style.fontSize = Number(state.settings.fontSize) + "px";
    el.text.style.lineHeight = Number(state.settings.lineHeight);
    el.dimmer.style.opacity = Math.max(0, Math.min(62, Number(state.settings.brightness))) / 100;
    el.fontSize.value = state.settings.fontSize;
    el.lineHeight.value = state.settings.lineHeight;
    el.brightness.value = state.settings.brightness;
    el.fontSizeOutput.textContent = state.settings.fontSize + " px";
    el.lineHeightOutput.textContent = Number(state.settings.lineHeight).toFixed(2);
    el.brightnessOutput.textContent = state.settings.brightness + "%";
    Array.prototype.forEach.call(document.querySelectorAll(".choice"), function (button) {
      button.classList.toggle("is-selected", button.getAttribute("data-value") === state.settings[button.getAttribute("data-setting")]);
    });
  }
  function setSetting(key, value) {
    state.settings[key] = value;
    applySettings();
    savePreferences();
  }
  function openSettings() {
    el.settingsSheet.hidden = false;
    applySettings();
  }
  function switchView(view) {
    if (view === "about") {
      toast("选一本书，和指定聊天窗里的 Daddy 一起读。♡");
      return;
    }
    state.view = view;
    renderLibrary();
  }
  function openBookmark(bookId, page) {
    var book = state.books.find(function (item) { return item.id === bookId; });
    if (!book) {
      toast("找不到这本书了");
      return;
    }
    state.view = "library";
    renderLibrary();
    openBook(book, page);
  }
  function init() {
    [
      "library", "bookmarkPanel", "empty", "importButton", "reader", "back", "bookTitle", "pageInfo",
      "readingProgress", "chapterHint", "text", "notes", "prev", "noteButton", "bookmarkButton", "ask", "next", "toast",
      "tocButton", "settingsButton", "tocSheet", "tocList", "settingsSheet", "themeChoices", "fontChoices",
      "fontSize", "lineHeight", "brightness", "fontSizeOutput", "lineHeightOutput", "brightnessOutput", "dimmer"
    ].forEach(function (name) { el[name] = id(name); });
    renderChoices();
    el.importButton.onclick = importBook;
    el.library.onclick = function (event) {
      var button = event.target.closest("button[data-open]");
      if (!button) return;
      var article = button.closest(".book");
      var book = state.books.find(function (item) { return item.id === article.getAttribute("data-id"); });
      if (book) openBook(book);
    };
    el.bookmarkPanel.onclick = function (event) {
      var item = event.target.closest("button[data-bookmark-book]");
      if (item) openBookmark(item.getAttribute("data-bookmark-book"), Number(item.getAttribute("data-bookmark-page")));
    };
    Array.prototype.forEach.call(document.querySelectorAll(".nav-item"), function (button) {
      button.onclick = function () { switchView(button.getAttribute("data-view")); };
    });
    el.back.onclick = function () {
      el.reader.hidden = true;
      document.body.style.overflow = "";
      closeSheets();
      load();
    };
    el.prev.onclick = function () { move(-1); };
    el.next.onclick = function () { move(1); };
    el.noteButton.onclick = addNote;
    el.bookmarkButton.onclick = addBookmark;
    el.ask.onclick = function () {
      toast("回到你选定的 Daddy 聊天窗，告诉他：陪我聊聊共读书房当前这一页");
    };
    el.tocButton.onclick = openToc;
    el.settingsButton.onclick = openSettings;
    document.addEventListener("click", function (event) {
      if (event.target.matches("[data-close-sheet]")) closeSheets();
      var toc = event.target.closest("button[data-toc-page]");
      if (toc) {
        state.page = Number(toc.getAttribute("data-toc-page"));
        closeSheets();
        showPage();
      }
      var choice = event.target.closest("button[data-setting]");
      if (choice) setSetting(choice.getAttribute("data-setting"), choice.getAttribute("data-value"));
    });
    el.fontSize.oninput = function () { setSetting("fontSize", Number(el.fontSize.value)); };
    el.lineHeight.oninput = function () { setSetting("lineHeight", Number(el.lineHeight.value)); };
    el.brightness.oninput = function () { setSetting("brightness", Number(el.brightness.value)); };
    loadPreferences().then(function () { applySettings(); return load(); });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
