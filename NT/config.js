(function (w) {
  w.NT_CONFIG = {
  "verify_url": "https://www.noon-team.org/verify",
  "update_api": "http://192.168.1.124/github/local-r2",
  "installable_forms_base": "http://192.168.1.124/Github/_7zFo/",
  "update_secure": true,
  "console_debug": true,
  "server_wait_ms": 1800,
  "lucene_index_done": false,
  "updatbleformids": [
    "ar:mn33,und:vb6,fa:mn54"
  ],
  "cache_update": 1,
  "first_launch": false,
  "update_mode": "static_state",
  "app_type": "MN",
  "search_index_type": "lucene",
  "search_engin_type": "lucene",
  "forum_defaults": {
    "dir": "rtl",
    "show_in_viewer": false,
    "show_in_search": false,
    "show_in_index": false
  },
  "forum_overrides": {
    "mn33": {
      "dir": "rtl"
    },
    "mn24": {
      "dir": "ltr"
    },
    "vb4": {
	  "show_in_viewer": true,
      "show_in_search": true,
      "show_in_index": true
    },
	"vb2": {
      "show_in_search": false,
      "show_in_index": false
    },
    "vb5": {
      "show_in_search": false,
      "show_in_index": false
    },
    "vb6": {
      "show_in_search": false,
      "show_in_index": false
    }
  },
  "ui": {
    "searchTypeSelector": {
      "show": true,
      "options": {
        "full_index": true,
        "partial_index": true,
        "root_index": true,
        "wildcard_index": true
      }
    },
    "searchSortSelector": {
      "show": true,
      "options": {
        "occurrences": true,
        "relevance": true,
        "date_newest": true,
        "date_oldest": true
      }
    }
	,
	searchRanges: { show: true, showCustomButton: false },
	indexingOptionsSheet: { show: true }
  }
};
})(window);
