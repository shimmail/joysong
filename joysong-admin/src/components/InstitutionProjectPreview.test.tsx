import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import InstitutionProjectPreview from './InstitutionProjectPreview';
import type { InstitutionProjectPreviewModel } from '../types/projectRequests';

const preview: InstitutionProjectPreviewModel = {
  name: 'Updated Face Lift', category: 'Surgery', description: 'Project summary',
  tags: ['local', 'updated'], slogan: 'Natural result',
  detailContent: '<h2 class="unsafe">Details</h2><p onclick="alert(1)">Safe <strong style="color:red">strong</strong><script>bad()</script></p><a href="javascript:bad()">plain link text</a><style>body{display:none}</style>',
  coverImage: 'http://127.0.0.1:8080/images/projects/cover.jpg',
  images: ['https://cdn.example/gallery.jpg'], salesCount: 12,
  currency: 'USD', price: 1100, originalPrice: null, active: true,
  travelGroundServiceFee: 440,
};

afterEach(cleanup);

describe('InstitutionProjectPreview', () => {
  it('renders real preview images and safe rich detail without URLs or consumer actions', () => {
    const { container } = render(<InstitutionProjectPreview model={preview} />);

    expect(screen.getByRole('img', { name: '项目封面' })).toHaveAttribute('src', '/images/projects/cover.jpg');
    expect(screen.getByRole('img', { name: '项目图片 1' })).toHaveAttribute('src', 'https://cdn.example/gallery.jpg');
    expect(screen.queryByText(preview.coverImage!)).not.toBeInTheDocument();
    expect(screen.queryByText(preview.images[0])).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /预约|咨询|购买|上传|删除/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/预约|咨询|购买|上传|删除/)).not.toBeInTheDocument();

    expect(container.querySelector('h2')?.textContent).toBe('Details');
    expect(container.querySelector('strong')?.textContent).toBe('strong');
    expect(container.querySelector('h2')).not.toHaveAttribute('class');
    expect(container.querySelector('p')).not.toHaveAttribute('onclick');
    expect(container.querySelector('strong')).not.toHaveAttribute('style');
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('style')).toBeNull();
    expect(container.querySelector('a')).toBeNull();
    expect(screen.getByText('plain link text')).toBeInTheDocument();
    expect(screen.queryByText('bad()')).not.toBeInTheDocument();
  });

  it('replaces a failed image with a neutral placeholder instead of exposing the raw URL', () => {
    render(<InstitutionProjectPreview model={{ ...preview, images: [] }} />);
    const cover = screen.getByRole('img', { name: '项目封面' });

    fireEvent.error(cover);

    expect(cover.getAttribute('src')).toMatch(/^data:image\/svg\+xml/);
    expect(screen.queryByText(preview.coverImage!)).not.toBeInTheDocument();
  });
});
