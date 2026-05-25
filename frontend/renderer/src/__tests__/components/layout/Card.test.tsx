import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Card, CardHeader, CardBody, CardFooter } from '@/components/layout/Card'

describe('Card', () => {
  it('renders children', () => {
    render(<Card><p>Content</p></Card>)
    expect(screen.getByText('Content')).toBeInTheDocument()
  })

  it('applies data-testid', () => {
    render(<Card data-testid="my-card"><span>x</span></Card>)
    expect(screen.getByTestId('my-card')).toBeInTheDocument()
  })
})

describe('CardHeader', () => {
  it('renders title', () => {
    render(<Card><CardHeader title="Account Details" /></Card>)
    expect(screen.getByText('Account Details')).toBeInTheDocument()
  })

  it('renders subtitle when provided', () => {
    render(<Card><CardHeader title="Title" subtitle="Sub" /></Card>)
    expect(screen.getByText('Sub')).toBeInTheDocument()
  })
})

describe('CardBody', () => {
  it('renders children', () => {
    render(<Card><CardBody><p>Body content</p></CardBody></Card>)
    expect(screen.getByText('Body content')).toBeInTheDocument()
  })
})

describe('CardFooter', () => {
  it('renders children', () => {
    render(<Card><CardFooter><button>Save</button></CardFooter></Card>)
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
  })
})
