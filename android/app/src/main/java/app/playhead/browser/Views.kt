package app.playhead.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

private val INK = Color.rgb(15, 15, 15)
private val MUTED = Color.rgb(100, 100, 100)
private val FILL = Color.rgb(241, 241, 241)
private val LINE = Color.rgb(232, 232, 232)

private fun Context.ripple(): Int {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return tv.resourceId
}

private fun Context.iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
    setImageResource(icon)
    contentDescription = description
    scaleType = ImageView.ScaleType.FIT_CENTER
    setPadding(dp(12), dp(12), dp(12), dp(12))
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
    setBackgroundResource(tv.resourceId)
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    setOnClickListener { onClick() }
}

/** The new-tab page. Plain native views, so an empty tab costs no WebView at all. */
class StartPage(
    context: Context,
    private val open: (String) -> Unit,
    private val focusSearch: () -> Unit
) : ScrollView(context) {

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(56), context.dp(24), context.dp(40))
    }

    init {
        setBackgroundColor(Color.WHITE)
        isFillViewport = true
        addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun refresh(bookmarks: List<Bookmarks.Item>, recent: List<History.Entry>, engineName: String) {
        val ctx = context
        content.removeAllViews()
        val maxWidth = (ctx.resources.displayMetrics.widthPixels - ctx.dp(48)).coerceAtMost(ctx.dp(640))

        content.addView(TextView(ctx).apply {
            text = "Playhead"
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(INK)
            gravity = Gravity.CENTER
        })

        content.addView(TextView(ctx).apply {
            text = "Search $engineName or type a web address"
            textSize = 16f
            setTextColor(MUTED)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(26).toFloat()
                setColor(FILL)
            }
            isClickable = true
            setOnClickListener { focusSearch() }
            layoutParams = LinearLayout.LayoutParams(maxWidth, ctx.dp(52)).apply { topMargin = ctx.dp(28) }
        })

        val shortcuts = ArrayList<Pair<String, String>>()
        shortcuts.add("YouTube" to "https://www.youtube.com/")
        bookmarks.forEach { shortcuts.add(it.title.ifBlank { it.url } to it.url) }
        val cols = ((maxWidth) / ctx.dp(96)).coerceIn(3, 6)
        shortcuts.take(cols * 2).chunked(cols).forEachIndexed { index, rowItems ->
            content.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (index == 0) ctx.dp(28) else ctx.dp(4)
                }
                rowItems.forEach { (label, url) -> addView(tile(ctx, label, url)) }
                // Keep the columns even when the last row is short.
                repeat(cols - rowItems.size) {
                    addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                }
            })
        }

        if (recent.isNotEmpty()) {
            content.addView(TextView(ctx).apply {
                text = "Recent"
                textSize = 13f
                setTextColor(MUTED)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(32)
                    bottomMargin = ctx.dp(4)
                }
            })
            recent.forEach { e ->
                content.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                    setBackgroundResource(ctx.ripple())
                    isClickable = true
                    setOnClickListener { open(e.url) }
                    layoutParams = LinearLayout.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(TextView(ctx).apply {
                        text = e.title
                        textSize = 15f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(INK)
                    })
                    addView(TextView(ctx).apply {
                        text = Uri.parse(e.url).host?.removePrefix("www.") ?: e.url
                        textSize = 12f
                        maxLines = 1
                        setTextColor(MUTED)
                    })
                })
            }
        }
    }

    private fun tile(ctx: Context, label: String, url: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ctx.dp(4), ctx.dp(10), ctx.dp(4), ctx.dp(10))
        setBackgroundResource(ctx.ripple())
        isClickable = true
        setOnClickListener { open(url) }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(ctx).apply {
            text = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(INK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FILL)
            }
            layoutParams = LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52))
        })
        addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(INK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ctx.dp(6) }
        })
    }
}

/** A simple full-screen list: used for bookmarks, history and settings. */
class ListPanel(context: Context, onBack: () -> Unit) : LinearLayout(context) {

    class Row(
        val title: String,
        val subtitle: String? = null,
        val checked: Boolean? = null,
        val onClick: () -> Unit,
        val onLong: (() -> Unit)? = null
    )

    private val titleView = TextView(context)
    private val actionView = TextView(context)
    private val empty = TextView(context)
    private val recycler = RecyclerView(context)
    private var rows: List<Row> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)

        titleView.apply {
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(INK)
        }
        actionView.apply {
            textSize = 14f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            visibility = View.GONE
        }
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), context.dp(2), context.dp(8), context.dp(2))
            addView(context.iconButton(R.drawable.ic_back, "Back") { onBack() })
            addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionView)
        })
        addView(View(context).apply { setBackgroundColor(LINE) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)))

        empty.apply {
            text = "Nothing here yet"
            textSize = 15f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = Adapter()
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        addView(FrameLayout(context).apply {
            addView(recycler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun show(title: String, newRows: List<Row>, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        titleView.text = title
        if (actionLabel != null && onAction != null) {
            actionView.text = actionLabel
            actionView.setOnClickListener { onAction() }
            actionView.visibility = View.VISIBLE
        } else {
            actionView.visibility = View.GONE
        }
        update(newRows)
    }

    fun update(newRows: List<Row>) {
        rows = newRows
        recycler.adapter?.notifyDataSetChanged()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class VH(
        val box: LinearLayout,
        val title: TextView,
        val sub: TextView,
        val toggle: Switch
    ) : RecyclerView.ViewHolder(box)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val title = TextView(ctx).apply {
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(INK)
            }
            val sub = TextView(ctx).apply {
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(MUTED)
            }
            val toggle = Switch(ctx).apply {
                isClickable = false
                isFocusable = false
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(INK, Color.rgb(160, 160, 160)))
                trackTintList = ColorStateList(states, intArrayOf(Color.argb(90, 15, 15, 15), Color.rgb(222, 222, 222)))
            }
            val texts = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(title)
                addView(sub)
            }
            val box = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
                setBackgroundResource(ctx.ripple())
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(toggle)
            }
            return VH(box, title, sub, toggle)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.title.text = r.title
            h.sub.text = r.subtitle ?: ""
            h.sub.visibility = if (r.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            h.toggle.visibility = if (r.checked == null) View.GONE else View.VISIBLE
            h.toggle.isChecked = r.checked == true
            h.box.setOnClickListener { r.onClick() }
            if (r.onLong != null) {
                h.box.setOnLongClickListener {
                    r.onLong.invoke()
                    true
                }
            } else {
                h.box.setOnLongClickListener(null)
                h.box.isLongClickable = false
            }
        }
    }
}

/** The grid of open tabs. Recycled views and lazily loaded thumbnails keep 100+ tabs smooth. */
class TabSwitcher(
    context: Context,
    private val tabs: TabManager,
    private val onPick: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
    private val onNew: () -> Unit,
    private val onCloseAll: () -> Unit,
    onBack: () -> Unit
) : LinearLayout(context) {

    private val title = TextView(context)
    private val recycler = RecyclerView(context)
    private val layoutManager = GridLayoutManager(context, 2)
    private val adapter = Adapter()
    private var thumbHeight = context.dp(110)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        title.apply {
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(INK)
        }
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), context.dp(2), context.dp(8), context.dp(2))
            addView(context.iconButton(R.drawable.ic_back, "Back") { onBack() })
            addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "Close all"
                textSize = 14f
                setTextColor(INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
                setOnClickListener { onCloseAll() }
            })
            addView(context.iconButton(R.drawable.ic_add, "New tab") { onNew() })
        })
        addView(View(context).apply { setBackgroundColor(LINE) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)))

        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(8)
        // The default pool keeps only 5 spare cards. A fast fling over a 5-column grid recycles more than that and
        // would rebuild cards from scratch, which is the expensive part. Keep enough to reuse for every fling.
        recycler.recycledViewPool.setMaxRecycledViews(0, 40)
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        recycler.setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        recycler.clipToPadding = false
        addView(recycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(v: RecyclerView.ViewHolder, direction: Int) = closeAt(v.bindingAdapterPosition)
        }).attachToRecyclerView(recycler)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val columns = (w / context.dp(220)).coerceAtLeast(2)
        layoutManager.spanCount = columns
        val cardWidth = (w - context.dp(16)) / columns - context.dp(12)
        thumbHeight = (cardWidth * 10 / 16).coerceAtLeast(context.dp(60))
    }

    /** Call when it is shown, or when the tabs changed while it is open. */
    fun refresh(scrollToActive: Boolean) {
        adapter.notifyDataSetChanged()
        updateTitle()
        if (scrollToActive) {
            val i = tabs.tabs.indexOf(tabs.active)
            if (i >= 0) recycler.post { layoutManager.scrollToPositionWithOffset(i, context.dp(8)) }
        }
    }

    private fun updateTitle() {
        val n = tabs.tabs.size
        title.text = if (n == 1) "1 tab" else "$n tabs"
    }

    private fun closeAt(position: Int) {
        if (position == RecyclerView.NO_POSITION || position >= tabs.tabs.size) return
        val before = tabs.tabs.size
        onClose(tabs.tabs[position])
        if (tabs.tabs.size == before - 1) adapter.notifyItemRemoved(position) else adapter.notifyDataSetChanged()
        updateTitle()
    }

    private inner class VH(
        val card: LinearLayout,
        val name: TextView,
        val close: ImageButton,
        val thumb: ImageView
    ) : RecyclerView.ViewHolder(card) {
        var styledActive: Boolean? = null
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemCount() = tabs.tabs.size
        override fun getItemId(position: Int) = tabs.tabs[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val name = TextView(ctx).apply {
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(INK)
                setPadding(ctx.dp(10), 0, 0, 0)
            }
            val close = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = "Close tab"
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                setBackgroundColor(Color.TRANSPARENT)
            }
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(FILL)
            }
            val card = LinearLayout(ctx).apply {
                orientation = VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(12).toFloat()
                    setColor(Color.WHITE)
                    setStroke(ctx.dp(1), LINE)
                }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6)) }
                addView(LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(name, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(close, LayoutParams(ctx.dp(36), ctx.dp(36)))
                })
                addView(thumb, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(110)))
            }
            return VH(card, name, close, thumb)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val tab = tabs.tabs[position]
            h.name.text = tab.title.ifBlank { tab.url ?: "New tab" }
            val isActive = tab === tabs.active
            if (h.styledActive != isActive) {
                (h.card.background as GradientDrawable).setStroke(context.dp(if (isActive) 2 else 1), if (isActive) INK else LINE)
                h.styledActive = isActive
            }
            h.thumb.layoutParams.height = thumbHeight
            h.thumb.setImageBitmap(null)
            h.thumb.tag = tab.id
            tabs.loadThumb(tab.id) { bmp ->
                if (h.thumb.tag == tab.id) h.thumb.setImageBitmap(bmp)
            }
            h.card.setOnClickListener { onPick(tab) }
            h.close.setOnClickListener { closeAt(h.bindingAdapterPosition) }
        }
    }
}
