interface PaginationProps {
  totalCount: number
  limit: number
  offset: number
  onPageChange: (newOffset: number) => void
}

export function Pagination({ totalCount, limit, offset, onPageChange }: PaginationProps) {
  const totalPages = Math.ceil(totalCount / limit)
  const currentPage = Math.floor(offset / limit)

  if (totalPages <= 1) return null

  return (
    <nav className="osc-pagination" aria-label="Pagination">
      <button
        type="button"
        className="osc-pagination__btn"
        onClick={() => onPageChange(Math.max(0, offset - limit))}
        disabled={currentPage === 0}
        aria-label="Previous page"
      >
        &lsaquo;
      </button>
      <span className="osc-pagination__info">
        {currentPage + 1} / {totalPages}
      </span>
      <button
        type="button"
        className="osc-pagination__btn"
        onClick={() => onPageChange(offset + limit)}
        disabled={currentPage >= totalPages - 1}
        aria-label="Next page"
      >
        &rsaquo;
      </button>
    </nav>
  )
}
